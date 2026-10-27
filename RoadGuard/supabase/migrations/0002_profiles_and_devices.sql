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
