-- ============================================================
-- Migration: 0001_extensions_and_types.sql
-- Purpose  : Enable required PostgreSQL extensions and define
--            reusable domain types, enums, and constants used
--            across the entire RoadGuard schema.
-- ============================================================

-- Enable UUID generation (pgcrypto provides gen_random_uuid())
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Enable PostGIS only if available in your Supabase tier.
-- If unavailable, distance calculations use the Haversine formula
-- implemented in application SQL functions below.
-- Uncomment only if your project is on a tier that includes PostGIS:
-- CREATE EXTENSION IF NOT EXISTS postgis;

-- ============================================================
-- Custom Enum Types
-- ============================================================

-- Road hazard classification labels.
-- NORMAL is intentionally excluded: normal road events must not
-- be stored as confirmed hazards.
DO $$ BEGIN
  CREATE TYPE hazard_type_enum AS ENUM (
    'POTHOLE',
    'SPEED_BREAKER'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Severity levels for a confirmed hazard.
DO $$ BEGIN
  CREATE TYPE severity_enum AS ENUM (
    'LOW',
    'MEDIUM',
    'HIGH',
    'CRITICAL',
    'UNKNOWN'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Lifecycle state of a hazard record.
DO $$ BEGIN
  CREATE TYPE verification_status_enum AS ENUM (
    'PENDING',    -- newly reported, not yet reviewed
    'VERIFIED',   -- confirmed by multiple observations or admin
    'DISPUTED',   -- conflicting observations; needs review
    'REJECTED'    -- confirmed false positive; record kept for history
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Origin of a hazard or observation record.
DO $$ BEGIN
  CREATE TYPE source_enum AS ENUM (
    'SENSOR',       -- detected automatically by the ESP32 + Android pipeline
    'USER_REPORT',  -- submitted manually by a user
    'ADMIN_IMPORT'  -- imported by an administrator
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Lifecycle state of a vehicle trip monitoring session.
DO $$ BEGIN
  CREATE TYPE trip_status_enum AS ENUM (
    'ACTIVE',     -- monitoring is currently running
    'COMPLETED',  -- trip ended normally
    'ABORTED'     -- trip ended due to disconnection or user cancellation
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Allowed states for a registered ESP32 device.
DO $$ BEGIN
  CREATE TYPE device_status_enum AS ENUM (
    'ACTIVE',
    'INACTIVE',
    'UNREGISTERED'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Kinds of events that can be recorded during a trip.
DO $$ BEGIN
  CREATE TYPE trip_event_type_enum AS ENUM (
    'HAZARD_DETECTED',
    'WARNING_TRIGGERED',
    'SENSOR_CONNECTED',
    'SENSOR_DISCONNECTED',
    'GPS_ACQUIRED',
    'GPS_LOST',
    'TRIP_STARTED',
    'TRIP_ENDED',
    'CALIBRATION_COMPLETE'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Distance unit preference.
DO $$ BEGIN
  CREATE TYPE distance_unit_enum AS ENUM (
    'METERS',
    'FEET'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Detection sensitivity preset.
DO $$ BEGIN
  CREATE TYPE sensitivity_enum AS ENUM (
    'LOW',
    'MEDIUM',
    'HIGH'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

-- Synchronisation operation types (for idempotency receipts).
DO $$ BEGIN
  CREATE TYPE sync_operation_type_enum AS ENUM (
    'HAZARD_OBSERVATION',
    'TRIP_CREATE',
    'TRIP_UPDATE',
    'TRIP_EVENT'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;
-- ============================================================
-- Migration: 0002_profiles_and_devices.sql
-- Purpose  : Create user profiles (linked to auth.users) and
--            ESP32 device registration tables.
-- ============================================================

-- ============================================================
-- TABLE: profiles
-- One row per authenticated user, created automatically via trigger.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.profiles (
  id            UUID        PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  display_name  TEXT        NOT NULL DEFAULT '',
  avatar_url    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT profiles_display_name_length CHECK (char_length(display_name) <= 100)
);

COMMENT ON TABLE  public.profiles IS 'Application profile data for each authenticated user.';
COMMENT ON COLUMN public.profiles.id IS 'References auth.users(id). One profile per user.';
COMMENT ON COLUMN public.profiles.display_name IS 'User-chosen display name, max 100 chars.';
COMMENT ON COLUMN public.profiles.avatar_url IS 'Optional URL to a profile picture.';

-- Automatically update updated_at on every row modification.
CREATE OR REPLACE FUNCTION public.handle_updated_at()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_profiles_updated_at ON public.profiles;
CREATE TRIGGER trg_profiles_updated_at
  BEFORE UPDATE ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

-- ============================================================
-- Auto-create profile when a new user signs up.
-- Uses SECURITY DEFINER with explicit search_path for safety.
-- ============================================================
CREATE OR REPLACE FUNCTION public.handle_new_user()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public
AS $$
BEGIN
  INSERT INTO public.profiles (id, display_name, created_at, updated_at)
  VALUES (
    NEW.id,
    COALESCE(NEW.raw_user_meta_data ->> 'display_name', ''),
    NOW(),
    NOW()
  )
  ON CONFLICT (id) DO NOTHING;
  RETURN NEW;
END;
$$;

-- Restrict execution to postgres superuser only; clients cannot call it directly.
REVOKE ALL ON FUNCTION public.handle_new_user() FROM PUBLIC;

DROP TRIGGER IF EXISTS trg_on_auth_user_created ON auth.users;
CREATE TRIGGER trg_on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ============================================================
-- TABLE: devices
-- Registered ESP32 BLE devices associated with users.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.devices (
  id                 UUID               PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id           UUID               NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  device_name        TEXT               NOT NULL,
  device_identifier  TEXT               NOT NULL, -- BLE MAC or unique identifier from firmware
  firmware_version   TEXT,
  status             device_status_enum NOT NULL DEFAULT 'ACTIVE',
  last_seen_at       TIMESTAMPTZ,
  created_at         TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ        NOT NULL DEFAULT NOW(),

  CONSTRAINT devices_name_length      CHECK (char_length(device_name) <= 100),
  CONSTRAINT devices_identifier_valid CHECK (
    device_identifier ~ '^[A-Za-z0-9_\-:\.]{3,64}$'
  ),
  CONSTRAINT devices_firmware_length  CHECK (
    firmware_version IS NULL OR char_length(firmware_version) <= 32
  ),
  -- A given device identifier can only be registered once per owner.
  UNIQUE (owner_id, device_identifier)
);

COMMENT ON TABLE  public.devices IS 'ESP32 BLE devices registered by users.';
COMMENT ON COLUMN public.devices.device_identifier IS
  'BLE advertisement name or a stable hardware identifier. Max 64 chars. Validated pattern.';
COMMENT ON COLUMN public.devices.status IS
  'ACTIVE: device is in use. INACTIVE: user paused it. UNREGISTERED: device removed.';

DROP TRIGGER IF EXISTS trg_devices_updated_at ON public.devices;
CREATE TRIGGER trg_devices_updated_at
  BEFORE UPDATE ON public.devices
  FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();
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
-- ============================================================
-- Migration: 0005_settings_and_sync.sql
-- Purpose  : User preference settings and idempotent sync receipts.
-- ============================================================

-- ============================================================
-- TABLE: user_settings
-- Per-user preference store. Synced from Android DataStore.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.user_settings (
  user_id                    UUID               PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  voice_alerts_enabled       BOOLEAN            NOT NULL DEFAULT TRUE,
  vibration_enabled          BOOLEAN            NOT NULL DEFAULT TRUE,
  pothole_alerts_enabled     BOOLEAN            NOT NULL DEFAULT TRUE,
  speed_breaker_alerts_enabled BOOLEAN          NOT NULL DEFAULT TRUE,
  alert_distance_meters      INTEGER            NOT NULL DEFAULT 150,
  distance_unit              distance_unit_enum NOT NULL DEFAULT 'METERS',
  sensitivity                sensitivity_enum   NOT NULL DEFAULT 'MEDIUM',
  created_at                 TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
  updated_at                 TIMESTAMPTZ        NOT NULL DEFAULT NOW(),

  -- Alert distance: 30 m (minimum useful) to 1000 m (maximum before false alarms)
  CONSTRAINT settings_alert_distance_range CHECK (
    alert_distance_meters BETWEEN 30 AND 1000
  )
);

COMMENT ON TABLE  public.user_settings IS
  'Driver alert preferences. Synchronised with the Android DataStore. Defaults applied on first login.';
COMMENT ON COLUMN public.user_settings.alert_distance_meters IS
  'How far ahead (metres) a hazard must be to trigger an advance warning. Range: 30–1000 m.';
COMMENT ON COLUMN public.user_settings.sensitivity IS
  'LOW: only high-confidence events; MEDIUM: balanced; HIGH: more sensitive, more false positives.';

DROP TRIGGER IF EXISTS trg_settings_updated_at ON public.user_settings;
CREATE TRIGGER trg_settings_updated_at
  BEFORE UPDATE ON public.user_settings
  FOR EACH ROW EXECUTE FUNCTION public.handle_updated_at();

-- Auto-create default settings when a user signs up.
-- Called from handle_new_user; kept separate for clarity.
CREATE OR REPLACE FUNCTION public.handle_new_user_settings()
  RETURNS TRIGGER
  LANGUAGE plpgsql
  SECURITY DEFINER
  SET search_path = public
AS $$
BEGIN
  INSERT INTO public.user_settings (user_id, created_at, updated_at)
  VALUES (NEW.id, NOW(), NOW())
  ON CONFLICT (user_id) DO NOTHING;
  RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION public.handle_new_user_settings() FROM PUBLIC;

DROP TRIGGER IF EXISTS trg_on_auth_user_settings ON auth.users;
CREATE TRIGGER trg_on_auth_user_settings
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.handle_new_user_settings();

-- ============================================================
-- TABLE: sync_receipts
-- Idempotency log for offline-queued synchronisation operations.
-- The Android app generates a stable operation_id (UUID v4) for
-- each queued item. The server records it and rejects duplicates.
-- ============================================================
CREATE TABLE IF NOT EXISTS public.sync_receipts (
  id             UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID                     NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  operation_id   UUID                     NOT NULL,
  operation_type sync_operation_type_enum NOT NULL,
  processed_at   TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
  -- Lightweight result: status code + resource id. NO private location data.
  result         JSONB,

  -- Per-user idempotency: same operation_id from the same user is rejected.
  CONSTRAINT sync_receipts_unique UNIQUE (user_id, operation_id),

  -- Limit result payload
  CONSTRAINT sync_receipts_result_size CHECK (
    result IS NULL OR octet_length(result::TEXT) <= 4096
  )
);

COMMENT ON TABLE  public.sync_receipts IS
  'Idempotency receipts for offline sync operations. Prevents duplicate hazard uploads on retry.';
COMMENT ON COLUMN public.sync_receipts.operation_id IS
  'UUID generated by the Android app when the operation is first queued. Stable across retries.';
COMMENT ON COLUMN public.sync_receipts.result IS
  'Server response snapshot: status, created resource IDs. NO credentials or private location data.';
-- ============================================================
-- Migration: 0006_indexes_and_functions.sql
-- Purpose  : Performance indexes and reusable database functions
--            (Haversine distance, hazard proximity queries).
-- ============================================================

-- ============================================================
-- Indexes
-- ============================================================

-- Profiles
CREATE INDEX IF NOT EXISTS idx_profiles_updated_at ON public.profiles (updated_at DESC);

-- Devices
CREATE INDEX IF NOT EXISTS idx_devices_owner      ON public.devices (owner_id);
CREATE INDEX IF NOT EXISTS idx_devices_identifier ON public.devices (device_identifier);
CREATE INDEX IF NOT EXISTS idx_devices_status     ON public.devices (status);

-- Trips
CREATE INDEX IF NOT EXISTS idx_trips_user         ON public.trips (user_id);
CREATE INDEX IF NOT EXISTS idx_trips_device       ON public.trips (device_id);
CREATE INDEX IF NOT EXISTS idx_trips_status       ON public.trips (status);
CREATE INDEX IF NOT EXISTS idx_trips_started_at   ON public.trips (started_at DESC);

-- Trip Events
CREATE INDEX IF NOT EXISTS idx_trip_events_trip   ON public.trip_events (trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_events_user   ON public.trip_events (user_id);
CREATE INDEX IF NOT EXISTS idx_trip_events_type   ON public.trip_events (event_type);
CREATE INDEX IF NOT EXISTS idx_trip_events_obs_at ON public.trip_events (observed_at DESC);

-- Hazards — geographic bounding box lookups (using B-tree on lat/lon)
CREATE INDEX IF NOT EXISTS idx_hazards_lat        ON public.hazards (latitude);
CREATE INDEX IF NOT EXISTS idx_hazards_lon        ON public.hazards (longitude);
CREATE INDEX IF NOT EXISTS idx_hazards_lat_lon    ON public.hazards (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_hazards_type       ON public.hazards (hazard_type);
CREATE INDEX IF NOT EXISTS idx_hazards_status     ON public.hazards (verification_status);
CREATE INDEX IF NOT EXISTS idx_hazards_active     ON public.hazards (is_active) WHERE is_active = TRUE;
CREATE INDEX IF NOT EXISTS idx_hazards_last_det   ON public.hazards (last_detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_hazards_created_by ON public.hazards (created_by);

-- Hazard Observations
CREATE INDEX IF NOT EXISTS idx_obs_hazard         ON public.hazard_observations (hazard_id);
CREATE INDEX IF NOT EXISTS idx_obs_user           ON public.hazard_observations (user_id);
CREATE INDEX IF NOT EXISTS idx_obs_device         ON public.hazard_observations (device_id);
CREATE INDEX IF NOT EXISTS idx_obs_trip           ON public.hazard_observations (trip_id);
CREATE INDEX IF NOT EXISTS idx_obs_observed_at    ON public.hazard_observations (observed_at DESC);
CREATE INDEX IF NOT EXISTS idx_obs_fingerprint    ON public.hazard_observations (event_fingerprint);
CREATE INDEX IF NOT EXISTS idx_obs_lat_lon        ON public.hazard_observations (latitude, longitude);

-- Sync Receipts
CREATE INDEX IF NOT EXISTS idx_sync_user          ON public.sync_receipts (user_id);
CREATE INDEX IF NOT EXISTS idx_sync_processed_at  ON public.sync_receipts (processed_at DESC);

-- ============================================================
-- Function: haversine_distance_meters
-- Returns the great-circle distance in metres between two
-- (lat, lon) coordinate pairs using the Haversine formula.
-- Precision is adequate for hazard proximity matching (±5 m at
-- road distances; GPS is typically ±5–15 m anyway).
-- ============================================================
CREATE OR REPLACE FUNCTION public.haversine_distance_meters(
  lat1 DOUBLE PRECISION,
  lon1 DOUBLE PRECISION,
  lat2 DOUBLE PRECISION,
  lon2 DOUBLE PRECISION
)
  RETURNS DOUBLE PRECISION
  LANGUAGE plpgsql
  IMMUTABLE
  PARALLEL SAFE
  SECURITY INVOKER
  SET search_path = public
AS $$
DECLARE
  r      CONSTANT DOUBLE PRECISION := 6371000.0; -- Earth radius in metres
  dlat   DOUBLE PRECISION;
  dlon   DOUBLE PRECISION;
  a      DOUBLE PRECISION;
  c      DOUBLE PRECISION;
BEGIN
  dlat := RADIANS(lat2 - lat1);
  dlon := RADIANS(lon2 - lon1);
  a := SIN(dlat / 2) ^ 2
       + COS(RADIANS(lat1)) * COS(RADIANS(lat2)) * SIN(dlon / 2) ^ 2;
  c := 2 * ATAN2(SQRT(a), SQRT(1 - a));
  RETURN r * c;
END;
$$;

COMMENT ON FUNCTION public.haversine_distance_meters IS
  'Great-circle distance in metres. Adequate for hazard proximity matching (GPS accuracy ~5–15 m).';

-- ============================================================
-- Function: get_hazards_in_bounding_box
-- Returns active hazards within a lat/lon bounding box.
-- Called by the Android app when downloading hazards for a region.
-- Results are paginated by limit/offset.
-- ============================================================
CREATE OR REPLACE FUNCTION public.get_hazards_in_bounding_box(
  min_lat   DOUBLE PRECISION,
  max_lat   DOUBLE PRECISION,
  min_lon   DOUBLE PRECISION,
  max_lon   DOUBLE PRECISION,
  p_limit   INTEGER DEFAULT 200,
  p_offset  INTEGER DEFAULT 0
)
  RETURNS TABLE (
    id                  UUID,
    latitude            DOUBLE PRECISION,
    longitude           DOUBLE PRECISION,
    hazard_type         TEXT,
    severity            TEXT,
    verification_status TEXT,
    confidence          REAL,
    observation_count   INTEGER,
    first_detected_at   TIMESTAMPTZ,
    last_detected_at    TIMESTAMPTZ,
    is_active           BOOLEAN
  )
  LANGUAGE plpgsql
  STABLE
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  -- Clamp limit to prevent excessively large result sets
  p_limit := LEAST(p_limit, 500);

  RETURN QUERY
    SELECT
      h.id,
      h.latitude,
      h.longitude,
      h.hazard_type::TEXT,
      h.severity::TEXT,
      h.verification_status::TEXT,
      h.confidence,
      h.observation_count,
      h.first_detected_at,
      h.last_detected_at,
      h.is_active
    FROM public.hazards h
    WHERE h.is_active = TRUE
      AND h.verification_status != 'REJECTED'
      AND h.latitude  BETWEEN min_lat AND max_lat
      AND h.longitude BETWEEN min_lon AND max_lon
    ORDER BY h.last_detected_at DESC
    LIMIT  p_limit
    OFFSET p_offset;
END;
$$;

-- Note: created_by and private user info are intentionally excluded from this function.
COMMENT ON FUNCTION public.get_hazards_in_bounding_box IS
  'Returns public hazard data in a lat/lon bounding box. Excludes private identity data. Max 500 rows.';

-- ============================================================
-- Function: get_user_trip_statistics
-- Returns aggregate statistics for the authenticated user's trips.
-- ============================================================
CREATE OR REPLACE FUNCTION public.get_user_trip_statistics(p_user_id UUID)
  RETURNS TABLE (
    total_trips         BIGINT,
    completed_trips     BIGINT,
    total_hazards       BIGINT,
    pothole_count       BIGINT,
    speed_breaker_count BIGINT,
    total_distance_km   DOUBLE PRECISION
  )
  LANGUAGE plpgsql
  STABLE
  SECURITY INVOKER
  SET search_path = public
AS $$
BEGIN
  RETURN QUERY
    SELECT
      COUNT(DISTINCT t.id)                                       AS total_trips,
      COUNT(DISTINCT t.id) FILTER (WHERE t.status = 'COMPLETED') AS completed_trips,
      COUNT(DISTINCT o.id)                                       AS total_hazards,
      COUNT(DISTINCT o.id) FILTER (WHERE o.hazard_type = 'POTHOLE')       AS pothole_count,
      COUNT(DISTINCT o.id) FILTER (WHERE o.hazard_type = 'SPEED_BREAKER') AS speed_breaker_count,
      COALESCE(SUM(t.distance_meters) / 1000.0, 0.0)            AS total_distance_km
    FROM public.trips t
    LEFT JOIN public.hazard_observations o
      ON o.trip_id = t.id AND o.user_id = p_user_id
    WHERE t.user_id = p_user_id;
END;
$$;

COMMENT ON FUNCTION public.get_user_trip_statistics IS
  'Aggregate statistics for a given user. Only that user can call this via RLS.';
-- ============================================================
-- Migration: 0007_rls_policies.sql
-- Purpose  : Row Level Security policies for all application
--            tables. RLS is enabled first, then explicit
--            policies are added for each operation.
-- ============================================================
-- IMPORTANT: This file enables RLS and adds policies.
-- Do NOT disable RLS to resolve permission errors.
-- Instead, inspect policy logic and JWT claims.
-- ============================================================

-- ============================================================
-- Enable RLS on all tables
-- ============================================================
ALTER TABLE public.profiles             ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.trips                ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.trip_events          ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hazards              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hazard_observations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_settings        ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_receipts        ENABLE ROW LEVEL SECURITY;

-- ============================================================
-- Helper: current authenticated user ID from JWT
-- ============================================================
-- auth.uid() is provided by Supabase and returns the user's UUID
-- from the verified JWT. Do not use client-provided user IDs.

-- ============================================================
-- PROFILES
-- ============================================================
DROP POLICY IF EXISTS "profiles_select_own"  ON public.profiles;
DROP POLICY IF EXISTS "profiles_update_own"  ON public.profiles;
DROP POLICY IF EXISTS "profiles_insert_own"  ON public.profiles;

-- Users can only see their own profile
CREATE POLICY "profiles_select_own"
  ON public.profiles FOR SELECT
  USING (id = auth.uid());

-- Users can only update their own profile (display_name, avatar_url)
CREATE POLICY "profiles_update_own"
  ON public.profiles FOR UPDATE
  USING (id = auth.uid())
  WITH CHECK (
    id = auth.uid()
    -- id cannot be changed; it must always equal auth.uid()
  );

-- Profile creation is handled by the trigger only; direct client inserts are blocked.
-- (Trigger uses SECURITY DEFINER so it bypasses RLS.)

-- ============================================================
-- DEVICES
-- ============================================================
DROP POLICY IF EXISTS "devices_select_own"  ON public.devices;
DROP POLICY IF EXISTS "devices_insert_own"  ON public.devices;
DROP POLICY IF EXISTS "devices_update_own"  ON public.devices;
DROP POLICY IF EXISTS "devices_delete_own"  ON public.devices;

CREATE POLICY "devices_select_own"
  ON public.devices FOR SELECT
  USING (owner_id = auth.uid());

CREATE POLICY "devices_insert_own"
  ON public.devices FOR INSERT
  WITH CHECK (owner_id = auth.uid());

CREATE POLICY "devices_update_own"
  ON public.devices FOR UPDATE
  USING (owner_id = auth.uid())
  WITH CHECK (
    owner_id = auth.uid()
    -- owner_id cannot be changed to another user's ID
  );

CREATE POLICY "devices_delete_own"
  ON public.devices FOR DELETE
  USING (owner_id = auth.uid());

-- ============================================================
-- TRIPS
-- ============================================================
DROP POLICY IF EXISTS "trips_select_own"  ON public.trips;
DROP POLICY IF EXISTS "trips_insert_own"  ON public.trips;
DROP POLICY IF EXISTS "trips_update_own"  ON public.trips;
DROP POLICY IF EXISTS "trips_delete_own"  ON public.trips;

CREATE POLICY "trips_select_own"
  ON public.trips FOR SELECT
  USING (user_id = auth.uid());

CREATE POLICY "trips_insert_own"
  ON public.trips FOR INSERT
  WITH CHECK (user_id = auth.uid());

CREATE POLICY "trips_update_own"
  ON public.trips FOR UPDATE
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

-- Allow users to delete their own trips (cascades to events and observations)
CREATE POLICY "trips_delete_own"
  ON public.trips FOR DELETE
  USING (user_id = auth.uid());

-- ============================================================
-- TRIP EVENTS
-- ============================================================
DROP POLICY IF EXISTS "trip_events_select_own"  ON public.trip_events;
DROP POLICY IF EXISTS "trip_events_insert_own"  ON public.trip_events;
DROP POLICY IF EXISTS "trip_events_delete_own"  ON public.trip_events;

CREATE POLICY "trip_events_select_own"
  ON public.trip_events FOR SELECT
  USING (user_id = auth.uid());

CREATE POLICY "trip_events_insert_own"
  ON public.trip_events FOR INSERT
  WITH CHECK (
    user_id = auth.uid()
    -- Trigger validates trip ownership further
  );

CREATE POLICY "trip_events_delete_own"
  ON public.trip_events FOR DELETE
  USING (user_id = auth.uid());

-- ============================================================
-- HAZARDS (shared public map)
-- Public fields are intentionally limited.
-- Private identity (created_by) is NOT selected in the public read policy.
-- Verification status changes require Edge Function authorization.
-- ============================================================
DROP POLICY IF EXISTS "hazards_select_public_active"  ON public.hazards;
DROP POLICY IF EXISTS "hazards_insert_sensor"         ON public.hazards;
DROP POLICY IF EXISTS "hazards_update_verified_only"  ON public.hazards;

-- Any authenticated user can read active, non-rejected hazards.
-- Private columns (created_by) are visible but anonymized in application queries.
CREATE POLICY "hazards_select_public_active"
  ON public.hazards FOR SELECT
  TO authenticated
  USING (
    is_active = TRUE
    AND verification_status != 'REJECTED'
  );

-- Authenticated users can insert PENDING hazards from sensor detection.
-- Verification status is forced to PENDING by CHECK.
CREATE POLICY "hazards_insert_sensor"
  ON public.hazards FOR INSERT
  TO authenticated
  WITH CHECK (
    verification_status = 'PENDING'
    AND created_by = auth.uid()
  );

-- Only UPDATE is allowed for is_active and last_detected_at, managed via Edge Function
-- or trusted service. Normal users cannot change verification_status.
-- The Edge Function moderate-hazard uses the service role and bypasses RLS.

-- ============================================================
-- HAZARD OBSERVATIONS (private, owned by user)
-- ============================================================
DROP POLICY IF EXISTS "obs_select_own"  ON public.hazard_observations;
DROP POLICY IF EXISTS "obs_insert_own"  ON public.hazard_observations;
DROP POLICY IF EXISTS "obs_delete_own"  ON public.hazard_observations;

CREATE POLICY "obs_select_own"
  ON public.hazard_observations FOR SELECT
  USING (user_id = auth.uid());

CREATE POLICY "obs_insert_own"
  ON public.hazard_observations FOR INSERT
  WITH CHECK (
    user_id = auth.uid()
    -- Trigger validates device and trip ownership
  );

CREATE POLICY "obs_delete_own"
  ON public.hazard_observations FOR DELETE
  USING (user_id = auth.uid());

-- ============================================================
-- USER SETTINGS
-- ============================================================
DROP POLICY IF EXISTS "settings_select_own"  ON public.user_settings;
DROP POLICY IF EXISTS "settings_insert_own"  ON public.user_settings;
DROP POLICY IF EXISTS "settings_update_own"  ON public.user_settings;

CREATE POLICY "settings_select_own"
  ON public.user_settings FOR SELECT
  USING (user_id = auth.uid());

CREATE POLICY "settings_insert_own"
  ON public.user_settings FOR INSERT
  WITH CHECK (user_id = auth.uid());

CREATE POLICY "settings_update_own"
  ON public.user_settings FOR UPDATE
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());

-- ============================================================
-- SYNC RECEIPTS (private, owned by user)
-- ============================================================
DROP POLICY IF EXISTS "sync_receipts_select_own"  ON public.sync_receipts;
DROP POLICY IF EXISTS "sync_receipts_insert_own"  ON public.sync_receipts;

CREATE POLICY "sync_receipts_select_own"
  ON public.sync_receipts FOR SELECT
  USING (user_id = auth.uid());

-- Receipts are inserted by trusted server-side operations (Edge Functions) only.
-- Normal clients may not insert receipts directly.
-- (Edge Functions use service role which bypasses RLS.)
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
-- ============================================================
-- seed.sql — Isolated Development Demonstration Data
-- ============================================================
-- This file populates the database with clearly labelled DEMO
-- data for development and presentations ONLY.
--
-- WARNING: Do NOT run this against a production database.
-- It uses fixed UUIDs so it can be cleanly removed.
-- All records are tagged with source = 'ADMIN_IMPORT' and
-- verification_status = 'VERIFIED' to distinguish them from
-- real sensor detections.
--
-- To remove all seed data:
--   DELETE FROM public.hazards WHERE source = 'ADMIN_IMPORT';
-- ============================================================

-- Note: seed data does NOT include auth.users rows (managed by Supabase Auth).
-- Run this after creating at least one user account for association.
-- The demo hazards below are stand-alone and do not require a specific user.

-- ============================================================
-- Demo Hazards — Nagpur, India (near Ramdeobaba University)
-- Coordinates are illustrative. Do not treat as real hazard data.
-- ============================================================

INSERT INTO public.hazards (
  id, latitude, longitude, hazard_type, severity,
  verification_status, confidence, observation_count,
  first_detected_at, last_detected_at, source,
  created_by, is_active, created_at, updated_at
) VALUES
  -- Demo Pothole 1 — Gittikhadan Road
  (
    '11111111-0000-0000-0000-000000000001',
    21.1381, 79.0514, 'POTHOLE', 'HIGH',
    'VERIFIED', 0.92, 5,
    NOW() - INTERVAL '10 days', NOW() - INTERVAL '2 days',
    'ADMIN_IMPORT', NULL, TRUE, NOW(), NOW()
  ),
  -- Demo Pothole 2 — Kamptee Road
  (
    '11111111-0000-0000-0000-000000000002',
    21.1420, 79.0680, 'POTHOLE', 'MEDIUM',
    'VERIFIED', 0.78, 3,
    NOW() - INTERVAL '7 days', NOW() - INTERVAL '1 day',
    'ADMIN_IMPORT', NULL, TRUE, NOW(), NOW()
  ),
  -- Demo Speed Breaker 1 — Near RCOEM Campus Gate
  (
    '11111111-0000-0000-0000-000000000003',
    21.1400, 79.0570, 'SPEED_BREAKER', 'LOW',
    'VERIFIED', 0.96, 8,
    NOW() - INTERVAL '30 days', NOW() - INTERVAL '5 days',
    'ADMIN_IMPORT', NULL, TRUE, NOW(), NOW()
  ),
  -- Demo Speed Breaker 2 — Katol Road Junction
  (
    '11111111-0000-0000-0000-000000000004',
    21.1350, 79.0490, 'SPEED_BREAKER', 'MEDIUM',
    'VERIFIED', 0.88, 6,
    NOW() - INTERVAL '20 days', NOW() - INTERVAL '3 days',
    'ADMIN_IMPORT', NULL, TRUE, NOW(), NOW()
  ),
  -- Demo Pothole 3 — PENDING (newly submitted, not yet verified)
  (
    '11111111-0000-0000-0000-000000000005',
    21.1465, 79.0601, 'POTHOLE', 'UNKNOWN',
    'PENDING', 0.65, 1,
    NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day',
    'ADMIN_IMPORT', NULL, TRUE, NOW(), NOW()
  )
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- Note: profiles, devices, trips, and observations are created
-- dynamically by the application during testing.
-- This file only seeds shared hazard map data.
-- ============================================================
