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
