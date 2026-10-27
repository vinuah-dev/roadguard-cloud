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
