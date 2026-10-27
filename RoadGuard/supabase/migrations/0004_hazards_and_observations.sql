-- ============================================================
-- Migration: 0004_hazards_and_observations.sql
-- Purpose  : Shared hazard map and individual observations that
--            contribute to or update hazard records.
-- ============================================================

-- ============================================================
-- TABLE: hazards
-- The shared, de-duplicated road hazard map.
-- One row per distinct physical hazard location.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.hazards (
  id                  UUID                      PRIMARY KEY DEFAULT gen_random_uuid(),
  latitude            DOUBLE PRECISION          NOT NULL,
  longitude           DOUBLE PRECISION          NOT NULL,
  hazard_type         hazard_type_enum          NOT NULL,
  severity            severity_enum             NOT NULL DEFAULT 'UNKNOWN',
  verification_status verification_status_enum  NOT NULL DEFAULT 'PENDING',
  confidence          REAL,
  observation_count   INTEGER                   NOT NULL DEFAULT 1,
  first_detected_at   TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
  last_detected_at    TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
  source              source_enum               NOT NULL DEFAULT 'SENSOR',
  created_by          UUID                      REFERENCES auth.users(id) ON DELETE SET NULL,
  is_active           BOOLEAN                   NOT NULL DEFAULT TRUE,
  created_at          TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ               NOT NULL DEFAULT NOW(),

  -- Geographic range validation
  CONSTRAINT hazards_lat_range        CHECK (latitude  BETWEEN -90  AND 90),
  CONSTRAINT hazards_lon_range        CHECK (longitude BETWEEN -180 AND 180),

  -- Confidence 0.0–1.0 when provided
  CONSTRAINT hazards_confidence_range CHECK (confidence IS NULL OR (confidence BETWEEN 0.0 AND 1.0)),

  -- Observation count must be positive
  CONSTRAINT hazards_obs_count_pos    CHECK (observation_count >= 1),

  -- Temporal ordering
  CONSTRAINT hazards_temporal_order   CHECK (last_detected_at >= first_detected_at)
);

COMMENT ON TABLE  public.hazards IS
  'De-duplicated shared road hazard map. One row per distinct physical hazard.';
COMMENT ON COLUMN public.hazards.hazard_type IS
  'POTHOLE or SPEED_BREAKER. NORMAL road events are never stored here.';
COMMENT ON COLUMN public.hazards.verification_status IS
  'PENDING: new, unreviewed. VERIFIED: confirmed. DISPUTED: conflicting reports. REJECTED: false positive.';
COMMENT ON COLUMN public.hazards.confidence IS
  'Model confidence 0.0–1.0 at the time of the classifying observation. NULL if not available.';
COMMENT ON COLUMN public.hazards.observation_count IS
  'Number of distinct observations that reference this hazard record.';
COMMENT ON COLUMN public.hazards.is_active IS
  'FALSE means the hazard may no longer exist; kept for historical reference.';
COMMENT ON COLUMN public.hazards.created_by IS
  'Auth user who first reported this hazard. Nullable if the originating user is deleted.';

DROP TRIGGER IF EXISTS trg_hazards_updated_at ON public.hazards;
CREATE TRIGGER trg_hazards_updated_at
  BEFORE UPDATE ON public.hazards
  FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

-- ============================================================
-- TABLE: hazard_observations
-- Each row is one sensor-based or user-submitted observation.
-- Multiple observations can be linked to the same hazard.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.hazard_observations (
  id                UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
  hazard_id         UUID             REFERENCES public.hazards(id) ON DELETE SET NULL,
  user_id           UUID             NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  device_id         UUID             REFERENCES public.devices(id) ON DELETE SET NULL,
  trip_id           UUID             REFERENCES public.trips(id) ON DELETE SET NULL,
  latitude          DOUBLE PRECISION NOT NULL,
  longitude         DOUBLE PRECISION NOT NULL,
  hazard_type       hazard_type_enum NOT NULL,
  severity          severity_enum    NOT NULL DEFAULT 'UNKNOWN',
  confidence        REAL,
  observed_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
  source            source_enum      NOT NULL DEFAULT 'SENSOR',
  -- Idempotency key: hash of device + timestamp + coordinates to prevent duplicate uploads
  event_fingerprint TEXT             NOT NULL,
  -- Compact JSONB feature snapshot for ML evaluation (not full raw samples)
  sensor_features   JSONB,
  created_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),

  -- Geographic range validation
  CONSTRAINT obs_lat_range        CHECK (latitude  BETWEEN -90  AND 90),
  CONSTRAINT obs_lon_range        CHECK (longitude BETWEEN -180 AND 180),

  -- Confidence range
  CONSTRAINT obs_confidence_range CHECK (confidence IS NULL OR (confidence BETWEEN 0.0 AND 1.0)),

  -- Idempotency key must be unique per user to prevent duplicate uploads
  CONSTRAINT obs_fingerprint_unique UNIQUE (user_id, event_fingerprint),

  -- Limit sensor_features payload (16 KB max)
  CONSTRAINT obs_features_size CHECK (
    sensor_features IS NULL OR octet_length(sensor_features::TEXT) <= 16384
  ),

  -- event_fingerprint must be non-empty and within a sensible length
  CONSTRAINT obs_fingerprint_format CHECK (char_length(event_fingerprint) BETWEEN 8 AND 128)
);

COMMENT ON TABLE  public.hazard_observations IS
  'Individual sensor readings or user reports that contribute to the hazard map.';
COMMENT ON COLUMN public.hazard_observations.hazard_id IS
  'References the de-duplicated hazard this observation is matched to. NULL if unresolved.';
COMMENT ON COLUMN public.hazard_observations.event_fingerprint IS
  'SHA-256 or similar hash preventing duplicate uploads. Unique per user.';
COMMENT ON COLUMN public.hazard_observations.sensor_features IS
  'Compact feature snapshot: peak_z, variance, jerk, speed_kmh, etc. NOT full sensor arrays. Max 16 KB.';

-- ============================================================
-- Validate that the observation's device_id and trip_id belong
-- to the same user who is submitting the observation.
-- User identity is derived from the authenticated JWT, not client input.
-- ============================================================
CREATE OR REPLACE FUNCTION public.validate_observation_ownership()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  -- Validate device ownership
  IF NEW.device_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.devices
      WHERE id = NEW.device_id AND owner_id = NEW.user_id
    ) THEN
      RAISE EXCEPTION 'device_id % does not belong to user %', NEW.device_id, NEW.user_id
        USING ERRCODE = 'foreign_key_violation';
    END IF;
  END IF;

  -- Validate trip ownership
  IF NEW.trip_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.trips
      WHERE id = NEW.trip_id AND user_id = NEW.user_id
    ) THEN
      RAISE EXCEPTION 'trip_id % does not belong to user %', NEW.trip_id, NEW.user_id
        USING ERRCODE = 'foreign_key_violation';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_obs_ownership ON public.hazard_observations;
CREATE TRIGGER trg_obs_ownership
  BEFORE INSERT OR UPDATE ON public.hazard_observations
  FOR EACH ROW EXECUTE FUNCTION public.validate_observation_ownership();
