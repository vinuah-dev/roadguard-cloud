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
