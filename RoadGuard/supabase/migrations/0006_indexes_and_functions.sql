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
