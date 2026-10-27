-- ============================================================
-- Migration: 0003_trips_and_events.sql
-- Purpose  : Vehicle trip monitoring sessions and per-trip events.
-- ============================================================

-- ============================================================
-- TABLE: trips
-- Each row represents one Start Monitoring → Stop Monitoring session.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.trips (
  id               UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID             NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  device_id        UUID             REFERENCES public.devices(id) ON DELETE SET NULL,
  status           trip_status_enum NOT NULL DEFAULT 'ACTIVE',
  started_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
  ended_at         TIMESTAMPTZ,
  start_latitude   DOUBLE PRECISION,
  start_longitude  DOUBLE PRECISION,
  end_latitude     DOUBLE PRECISION,
  end_longitude    DOUBLE PRECISION,
  distance_meters  DOUBLE PRECISION,
  created_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

  -- Coordinate range validation
  CONSTRAINT trips_start_lat_range  CHECK (start_latitude  IS NULL OR (start_latitude  BETWEEN -90  AND 90)),
  CONSTRAINT trips_start_lon_range  CHECK (start_longitude IS NULL OR (start_longitude BETWEEN -180 AND 180)),
  CONSTRAINT trips_end_lat_range    CHECK (end_latitude    IS NULL OR (end_latitude    BETWEEN -90  AND 90)),
  CONSTRAINT trips_end_lon_range    CHECK (end_longitude   IS NULL OR (end_longitude   BETWEEN -180 AND 180)),

  -- Temporal ordering: a trip cannot end before it starts
  CONSTRAINT trips_temporal_order   CHECK (ended_at IS NULL OR ended_at >= started_at),

  -- Distance must be non-negative when provided
  CONSTRAINT trips_distance_nonneg  CHECK (distance_meters IS NULL OR distance_meters >= 0),

  -- An ACTIVE trip has no ended_at; a COMPLETED or ABORTED trip requires it
  CONSTRAINT trips_status_ended_consistency CHECK (
    (status = 'ACTIVE' AND ended_at IS NULL) OR
    (status IN ('COMPLETED', 'ABORTED') AND ended_at IS NOT NULL)
  )
);

COMMENT ON TABLE  public.trips IS 'Monitoring sessions started by the driver in the RoadGuard app.';
COMMENT ON COLUMN public.trips.device_id IS
  'Nullable: set when an ESP32 device was connected during this trip.';
COMMENT ON COLUMN public.trips.distance_meters IS
  'Populated on trip completion when reliable GPS track is available. NULL when unavailable.';

DROP TRIGGER IF EXISTS trg_trips_updated_at ON public.trips;
CREATE TRIGGER trg_trips_updated_at
  BEFORE UPDATE ON public.trips
  FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

-- ============================================================
-- Validate that the device_id belongs to the same user as user_id.
-- Prevents a user from attaching another user's device to their trip.
-- ============================================================
CREATE OR REPLACE FUNCTION public.validate_trip_device_owner()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  IF NEW.device_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.devices
      WHERE id = NEW.device_id AND owner_id = NEW.user_id
    ) THEN
      RAISE EXCEPTION 'device_id % does not belong to user %', NEW.device_id, NEW.user_id
        USING ERRCODE = 'foreign_key_violation';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_trips_device_owner ON public.trips;
CREATE TRIGGER trg_trips_device_owner
  BEFORE INSERT OR UPDATE ON public.trips
  FOR EACH ROW EXECUTE FUNCTION public.validate_trip_device_owner();

-- ============================================================
-- TABLE: trip_events
-- High-level events within a trip (NOT raw sensor samples).
-- ============================================================
CREATE TABLE IF NOT EXISTS public.trip_events (
  id           UUID                  PRIMARY KEY DEFAULT gen_random_uuid(),
  trip_id      UUID                  NOT NULL REFERENCES public.trips(id) ON DELETE CASCADE,
  user_id      UUID                  NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  event_type   trip_event_type_enum  NOT NULL,
  hazard_id    UUID,                 -- populated for HAZARD_DETECTED and WARNING_TRIGGERED
  observed_at  TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
  latitude     DOUBLE PRECISION,
  longitude    DOUBLE PRECISION,
  metadata     JSONB,
  created_at   TIMESTAMPTZ           NOT NULL DEFAULT NOW(),

  CONSTRAINT trip_events_lat_range  CHECK (latitude  IS NULL OR (latitude  BETWEEN -90  AND 90)),
  CONSTRAINT trip_events_lon_range  CHECK (longitude IS NULL OR (longitude BETWEEN -180 AND 180)),
  -- Limit metadata payload to prevent abuse (8 KB)
  CONSTRAINT trip_events_metadata_size CHECK (
    metadata IS NULL OR octet_length(metadata::TEXT) <= 8192
  )
);

COMMENT ON TABLE  public.trip_events IS
  'Discrete events recorded during a trip. Does NOT store raw accelerometer samples.';
COMMENT ON COLUMN public.trip_events.metadata IS
  'Optional JSONB supplementary data. Limited to 8 KB. No raw sensor arrays.';
