-- ==============================================================================
-- RoadGuard PostgreSQL 18 Database Schema & Functions
-- Database: Potholes DB
-- Department of Computer Science & Engineering | IDEA Lab
-- Ramdeobaba University, Academic Year 2026-27
-- Guide: Dr. Suresh Balpande
-- Team: Bhoomi Magarde, Mohammad Ejaz Mehendi, Nidhish Dadhe
-- ==============================================================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Try enabling PostGIS if installed (gracefully ignored if not yet installed)
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS postgis;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'PostGIS extension not found; using high-precision Haversine math function instead.';
END $$;

-- ------------------------------------------------------------------------------
-- 1. Custom Enum Types
-- ------------------------------------------------------------------------------
DO $$ BEGIN
    CREATE TYPE hazard_type_enum AS ENUM ('POTHOLE', 'SPEED_BREAKER');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE severity_enum AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE verification_status_enum AS ENUM ('PENDING', 'VERIFIED', 'DISPUTED', 'REJECTED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE trip_status_enum AS ENUM ('ACTIVE', 'COMPLETED', 'ABORTED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ------------------------------------------------------------------------------
-- 2. Core Tables
-- ------------------------------------------------------------------------------

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name  VARCHAR(100) NOT NULL DEFAULT 'Driver',
    email         VARCHAR(255) UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Registered IoT Devices (ESP32)
CREATE TABLE IF NOT EXISTS devices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID REFERENCES users(id) ON DELETE CASCADE,
    device_name        VARCHAR(100) NOT NULL DEFAULT 'RoadGuard-ESP32',
    device_identifier  VARCHAR(64) NOT NULL UNIQUE,
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_seen_at       TIMESTAMPTZ DEFAULT NOW(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Hazards (Deduplicated Master Road Anomalies)
CREATE TABLE IF NOT EXISTS hazards (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hazard_type          hazard_type_enum NOT NULL,
    latitude             DOUBLE PRECISION NOT NULL,
    longitude            DOUBLE PRECISION NOT NULL,
    severity             severity_enum NOT NULL DEFAULT 'HIGH',
    roughness_score      DOUBLE PRECISION NOT NULL DEFAULT 5.0,
    confirmations_count  INTEGER NOT NULL DEFAULT 1,
    verification_status  verification_status_enum NOT NULL DEFAULT 'PENDING',
    source               VARCHAR(30) NOT NULL DEFAULT 'SENSOR',
    address_description  TEXT DEFAULT '',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hazards_lat_lng ON hazards (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_hazards_type ON hazards (hazard_type);

-- Vehicle Trips
CREATE TABLE IF NOT EXISTS trips (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID REFERENCES users(id) ON DELETE SET NULL,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at              TIMESTAMPTZ,
    distance_meters       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    duration_seconds      INTEGER NOT NULL DEFAULT 0,
    potholes_count        INTEGER NOT NULL DEFAULT 0,
    speed_breakers_count  INTEGER NOT NULL DEFAULT 0,
    avg_roughness         DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    status                trip_status_enum NOT NULL DEFAULT 'COMPLETED',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Raw Sensor Hazard Observations (Every single vehicle detection instance)
CREATE TABLE IF NOT EXISTS hazard_observations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hazard_id        UUID REFERENCES hazards(id) ON DELETE CASCADE,
    trip_id          UUID REFERENCES trips(id) ON DELETE SET NULL,
    latitude         DOUBLE PRECISION NOT NULL,
    longitude        DOUBLE PRECISION NOT NULL,
    speed_kmh        DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    az_peak          DOUBLE PRECISION NOT NULL DEFAULT 9.8,
    roughness_score  DOUBLE PRECISION NOT NULL DEFAULT 5.0,
    observed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_obs_hazard_id ON hazard_observations (hazard_id);

-- Trip Events (Diagnostic & Telemetry markers during trips)
CREATE TABLE IF NOT EXISTS trip_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id     UUID REFERENCES trips(id) ON DELETE CASCADE,
    event_type  VARCHAR(50) NOT NULL,
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    speed_kmh   DOUBLE PRECISION,
    heading     DOUBLE PRECISION,
    payload     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_trip_events_trip_id ON trip_events (trip_id);

-- ------------------------------------------------------------------------------
-- 3. High-Precision Haversine Distance Function (Meters)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION haversine_distance(
    lat1 DOUBLE PRECISION, lon1 DOUBLE PRECISION,
    lat2 DOUBLE PRECISION, lon2 DOUBLE PRECISION
)
RETURNS DOUBLE PRECISION
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    r DOUBLE PRECISION := 6371000.0; -- Earth radius in meters
    phi1 DOUBLE PRECISION := radians(lat1);
    phi2 DOUBLE PRECISION := radians(lat2);
    delta_phi DOUBLE PRECISION := radians(lat2 - lat1);
    delta_lambda DOUBLE PRECISION := radians(lon2 - lon1);
    a DOUBLE PRECISION;
    c DOUBLE PRECISION;
BEGIN
    a := sin(delta_phi / 2.0)^2 + cos(phi1) * cos(phi2) * sin(delta_lambda / 2.0)^2;
    c := 2.0 * atan2(sqrt(a), sqrt(1.0 - a));
    RETURN r * c;
END;
$$;

-- ------------------------------------------------------------------------------
-- 4. Spatial Hazard Ingestion & Deduplication Routine
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ingest_hazard_observation(
    p_hazard_type VARCHAR,
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_severity VARCHAR,
    p_roughness DOUBLE PRECISION,
    p_speed_kmh DOUBLE PRECISION,
    p_az_peak DOUBLE PRECISION,
    p_trip_id UUID DEFAULT NULL,
    p_source VARCHAR DEFAULT 'SENSOR'
)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    v_hazard_id UUID;
    v_existing_conf INTEGER;
    v_new_conf INTEGER;
BEGIN
    -- NORMAL events are in-memory classification results, never persisted as confirmed hazards
    IF UPPER(p_hazard_type) = 'NORMAL' THEN
        RETURN NULL;
    END IF;

    -- Check if a hazard of same type exists within 15 meters radius
    SELECT id, confirmations_count
      INTO v_hazard_id, v_existing_conf
      FROM hazards
     WHERE hazard_type = p_hazard_type::hazard_type_enum
       AND haversine_distance(latitude, longitude, p_lat, p_lng) <= 15.0
     ORDER BY haversine_distance(latitude, longitude, p_lat, p_lng) ASC
     LIMIT 1;

    IF v_hazard_id IS NOT NULL THEN
        -- Cluster match: update confirmation count and recalculate roughness
        v_new_conf := v_existing_conf + 1;
        UPDATE hazards
           SET confirmations_count = v_new_conf,
               roughness_score = (roughness_score * 0.7) + (p_roughness * 0.3),
               verification_status = CASE WHEN v_new_conf >= 3 THEN 'VERIFIED'::verification_status_enum ELSE verification_status END,
               updated_at = NOW()
         WHERE id = v_hazard_id;
    ELSE
        -- New hazard: insert into hazards table
        INSERT INTO hazards (
            hazard_type, latitude, longitude, severity, roughness_score,
            confirmations_count, verification_status, source, created_at, updated_at
        ) VALUES (
            p_hazard_type::hazard_type_enum, p_lat, p_lng, p_severity::severity_enum,
            p_roughness, 1, 'PENDING'::verification_status_enum, p_source, NOW(), NOW()
        )
        RETURNING id INTO v_hazard_id;
    END IF;

    -- Record raw observation
    INSERT INTO hazard_observations (
        hazard_id, trip_id, latitude, longitude, speed_kmh, az_peak, roughness_score, observed_at
    ) VALUES (
        v_hazard_id, p_trip_id, p_lat, p_lng, p_speed_kmh, p_az_peak, p_roughness, NOW()
    );

    RETURN v_hazard_id;
END;
$$;

-- ------------------------------------------------------------------------------
-- 5. Academic Seed Data (Ramdeobaba University / Nagpur Landmarks)
-- ------------------------------------------------------------------------------
INSERT INTO users (id, display_name, email)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Mohammad Ejaz Mehendi', 'ejaz@roadguard.local'),
    ('a0000000-0000-0000-0000-000000000002', 'Bhoomi Magarde', 'bhoomi@roadguard.local'),
    ('a0000000-0000-0000-0000-000000000003', 'Nidhish Dadhe', 'nidhish@roadguard.local')
ON CONFLICT (id) DO NOTHING;

INSERT INTO hazards (hazard_type, latitude, longitude, severity, roughness_score, confirmations_count, verification_status, address_description)
VALUES
    ('POTHOLE', 21.1782, 79.0625, 'HIGH', 8.2, 18, 'VERIFIED', 'Near RCOEM Main Gate, Katol Road, Nagpur'),
    ('SPEED_BREAKER', 21.1765, 79.0608, 'MEDIUM', 5.6, 26, 'VERIFIED', 'Opposite Ramdeobaba Temple entrance curve'),
    ('POTHOLE', 21.1795, 79.0640, 'CRITICAL', 9.4, 32, 'VERIFIED', 'Gittikhadan Square outer intersection'),
    ('SPEED_BREAKER', 21.1750, 79.0590, 'LOW', 4.1, 12, 'VERIFIED', 'Near IDEA Lab engineering block'),
    ('POTHOLE', 21.1820, 79.0670, 'HIGH', 7.9, 15, 'VERIFIED', 'Friends Colony main road turn')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------------------
-- 6. Supabase Replacement Views & Compatibility Tables for Android & Web
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_settings (
    user_id                      UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    voice_alerts_enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    vibration_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    pothole_alerts_enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    speed_breaker_alerts_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    alert_distance_meters        INTEGER NOT NULL DEFAULT 150,
    distance_unit               VARCHAR(20) NOT NULL DEFAULT 'METERS',
    sensitivity                 VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed default user settings for team
INSERT INTO user_settings (user_id)
VALUES
    ('a0000000-0000-0000-0000-000000000001'),
    ('a0000000-0000-0000-0000-000000000002'),
    ('a0000000-0000-0000-0000-000000000003')
ON CONFLICT DO NOTHING;

-- Public Hazard Summary View (Direct replacement for Supabase public_hazard_summary)
CREATE OR REPLACE VIEW public_hazard_summary AS
SELECT
    id::text AS id,
    latitude,
    longitude,
    hazard_type::text AS hazard_type,
    severity::text AS severity,
    verification_status::text AS verification_status,
    0.95::real AS confidence,
    confirmations_count AS observation_count,
    to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS first_detected_at,
    to_char(updated_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS last_detected_at,
    source,
    TRUE AS is_active
FROM hazards;

-- Profiles View (Direct replacement for Supabase profiles)
CREATE OR REPLACE VIEW profiles AS
SELECT
    id::text AS id,
    display_name,
    NULL::text AS avatar_url,
    to_char(created_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS updated_at
FROM users;
