-- ============================================================
-- Migration: 0008_triggers_and_privileges.sql
-- Purpose  : Additional triggers, privilege grants, and
--            database-level access controls for the PostgREST
--            API role (anon / authenticated).
-- ============================================================

-- ============================================================
-- Trigger: auto-update trip_events to validate trip ownership
-- (belt-and-suspenders in addition to RLS)
-- ============================================================
CREATE OR REPLACE FUNCTION public.validate_trip_event_ownership()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM public.trips
    WHERE id = NEW.trip_id AND user_id = NEW.user_id
  ) THEN
    RAISE EXCEPTION 'trip_id % does not belong to user %', NEW.trip_id, NEW.user_id
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_trip_event_ownership ON public.trip_events;
CREATE TRIGGER trg_trip_event_ownership
  BEFORE INSERT OR UPDATE ON public.trip_events
  FOR EACH ROW EXECUTE FUNCTION public.validate_trip_event_ownership();

-- ============================================================
-- Trigger: increment hazard observation_count when a new
-- observation is linked to an existing hazard.
-- ============================================================
CREATE OR REPLACE FUNCTION public.increment_hazard_observation_count()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  -- Only update when a hazard_id is provided and this is a new observation
  IF NEW.hazard_id IS NOT NULL AND (TG_OP = 'INSERT' OR OLD.hazard_id IS DISTINCT FROM NEW.hazard_id) THEN
    UPDATE public.hazards
    SET
      observation_count = observation_count + 1,
      last_detected_at  = GREATEST(last_detected_at, NEW.observed_at),
      updated_at        = NOW()
    WHERE id = NEW.hazard_id;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_obs_increment_count ON public.hazard_observations;
CREATE TRIGGER trg_obs_increment_count
  AFTER INSERT OR UPDATE ON public.hazard_observations
  FOR EACH ROW EXECUTE FUNCTION public.increment_hazard_observation_count();

-- ============================================================
-- Grant minimal privileges to the PostgREST API roles.
-- anon: unauthenticated requests — very restricted
-- authenticated: verified JWT requests — standard CRUD via RLS
-- service_role: server-side Edge Functions — full access
-- ============================================================

-- Revoke all public defaults first
REVOKE ALL ON ALL TABLES    IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;

-- Grant schema usage
GRANT USAGE ON SCHEMA public TO anon, authenticated;

-- anon: no table access (unauthenticated users cannot read any table directly)
-- Supabase Auth handles login; authenticated sessions use the 'authenticated' role.

-- authenticated: access to tables (RLS policies apply)
GRANT SELECT, INSERT, UPDATE, DELETE ON public.profiles             TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.devices              TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.trips                TO authenticated;
GRANT SELECT, INSERT, DELETE         ON public.trip_events          TO authenticated;
GRANT SELECT, INSERT                 ON public.hazards              TO authenticated;
GRANT SELECT, INSERT, DELETE         ON public.hazard_observations  TO authenticated;
GRANT SELECT, INSERT, UPDATE         ON public.user_settings        TO authenticated;
GRANT SELECT                         ON public.sync_receipts        TO authenticated;

-- Function grants for authenticated users
GRANT EXECUTE ON FUNCTION public.get_hazards_in_bounding_box      TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_user_trip_statistics          TO authenticated;
GRANT EXECUTE ON FUNCTION public.haversine_distance_meters         TO authenticated;

-- service_role (used by Edge Functions via Supabase Admin client):
-- Full access managed by Supabase; no explicit grants needed since it bypasses RLS.
-- service_role NEVER touches client-side code.

-- ============================================================
-- View: public_hazard_summary
-- Safe subset of hazard columns for map display.
-- Intentionally excludes: created_by, observation details.
-- ============================================================
DROP VIEW IF EXISTS public.public_hazard_summary;
CREATE VIEW public.public_hazard_summary AS
  SELECT
    id,
    latitude,
    longitude,
    hazard_type,
    severity,
    verification_status,
    confidence,
    observation_count,
    first_detected_at,
    last_detected_at,
    is_active
  FROM public.hazards
  WHERE is_active = TRUE
    AND verification_status != 'REJECTED';

-- Grant authenticated users access to this view
GRANT SELECT ON public.public_hazard_summary TO authenticated;

COMMENT ON VIEW public.public_hazard_summary IS
  'Safe public view of active hazards. Excludes created_by and private identity columns.';
