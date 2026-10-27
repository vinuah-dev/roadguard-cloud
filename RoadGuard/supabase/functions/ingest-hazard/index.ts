// ============================================================
// Edge Function: ingest-hazard/index.ts
// Purpose: Trusted server-side hazard ingestion with full
//          validation, idempotency, and ownership checks.
//          Called by the Android app when submitting a new
//          sensor-detected hazard observation.
//
// This function uses the service role internally to:
//   1. Validate the authenticated user's JWT.
//   2. Validate device and trip ownership.
//   3. Check for duplicate submissions (event_fingerprint).
//   4. Create the hazard_observation record.
//   5. Create or update the shared hazard record.
//   6. Record a sync_receipt for idempotency.
//
// The service-role key is NEVER exposed to clients.
// Deno runtime — Supabase Edge Functions (Deno 1.x).
// ============================================================

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.7";

// ============================================================
// Types
// ============================================================
interface IngestHazardRequest {
  operationId: string;         // UUID v4 — stable idempotency key from Android
  deviceId?: string;           // UUID of the registered device (optional)
  tripId?: string;             // UUID of the active trip (optional)
  latitude: number;
  longitude: number;
  hazardType: "POTHOLE" | "SPEED_BREAKER";
  severity: "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN";
  confidence?: number;         // 0.0–1.0
  observedAt: string;          // ISO-8601 UTC timestamp
  eventFingerprint: string;    // SHA-256 or stable hash for dedup
  source?: "SENSOR" | "USER_REPORT";
  sensorFeatures?: Record<string, number>; // compact feature snapshot
}

interface ApiError {
  error: string;
  code: string;
  requestId?: string;
}

// ============================================================
// Constants
// ============================================================
const HAZARD_MERGE_RADIUS_METERS = 30; // Observations within 30 m are merged
const MAX_SENSOR_FEATURES_SIZE   = 16384; // 16 KB
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// ============================================================
// Validation Helpers
// ============================================================
function isValidUuid(value: string): boolean {
  return UUID_REGEX.test(value);
}

function isValidLatitude(v: number): boolean {
  return typeof v === "number" && isFinite(v) && v >= -90 && v <= 90;
}

function isValidLongitude(v: number): boolean {
  return typeof v === "number" && isFinite(v) && v >= -180 && v <= 180;
}

function isValidConfidence(v: number | undefined): boolean {
  return v === undefined || (typeof v === "number" && isFinite(v) && v >= 0 && v <= 1);
}

function errorResponse(status: number, code: string, message: string, requestId?: string): Response {
  const body: ApiError = { error: message, code, requestId };
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// Haversine distance in metres (mirrors the SQL function for server-side use)
function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dlat = toRad(lat2 - lat1);
  const dlon = toRad(lon2 - lon1);
  const a =
    Math.sin(dlat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dlon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ============================================================
// Main Handler
// ============================================================
serve(async (req: Request) => {
  const requestId = crypto.randomUUID();

  // Only accept POST
  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is allowed.", requestId);
  }

  // ---- 1. Authenticate the caller ----
  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return errorResponse(401, "MISSING_AUTH", "Authorization header required.", requestId);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey    = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

  if (!supabaseUrl || !anonKey || !serviceKey) {
    console.error("[ingest-hazard] Missing environment variables");
    return errorResponse(500, "CONFIG_ERROR", "Server configuration error.", requestId);
  }

  // User client — validates JWT and enforces RLS
  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false },
  });

  const { data: { user }, error: authError } = await userClient.auth.getUser();
  if (authError || !user) {
    return errorResponse(401, "INVALID_TOKEN", "Invalid or expired token.", requestId);
  }

  // Admin client — used only for trusted writes (service role, never sent to clients)
  const adminClient = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false },
  });

  // ---- 2. Parse and validate request body ----
  let body: IngestHazardRequest;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "INVALID_JSON", "Request body must be valid JSON.", requestId);
  }

  // Required field validation
  if (!body.operationId || !isValidUuid(body.operationId)) {
    return errorResponse(400, "INVALID_OPERATION_ID", "operationId must be a valid UUID.", requestId);
  }
  if (!isValidLatitude(body.latitude)) {
    return errorResponse(400, "INVALID_LATITUDE", "latitude must be between -90 and 90.", requestId);
  }
  if (!isValidLongitude(body.longitude)) {
    return errorResponse(400, "INVALID_LONGITUDE", "longitude must be between -180 and 180.", requestId);
  }
  if (!["POTHOLE", "SPEED_BREAKER"].includes(body.hazardType)) {
    return errorResponse(400, "INVALID_HAZARD_TYPE", "hazardType must be POTHOLE or SPEED_BREAKER.", requestId);
  }
  if (!["LOW", "MEDIUM", "HIGH", "UNKNOWN"].includes(body.severity)) {
    return errorResponse(400, "INVALID_SEVERITY", "severity must be LOW, MEDIUM, HIGH, or UNKNOWN.", requestId);
  }
  if (!isValidConfidence(body.confidence)) {
    return errorResponse(400, "INVALID_CONFIDENCE", "confidence must be between 0.0 and 1.0.", requestId);
  }
  if (!body.eventFingerprint || body.eventFingerprint.length < 8 || body.eventFingerprint.length > 128) {
    return errorResponse(400, "INVALID_FINGERPRINT", "eventFingerprint must be 8–128 chars.", requestId);
  }
  if (!body.observedAt || isNaN(Date.parse(body.observedAt))) {
    return errorResponse(400, "INVALID_TIMESTAMP", "observedAt must be a valid ISO-8601 timestamp.", requestId);
  }
  if (body.deviceId && !isValidUuid(body.deviceId)) {
    return errorResponse(400, "INVALID_DEVICE_ID", "deviceId must be a valid UUID.", requestId);
  }
  if (body.tripId && !isValidUuid(body.tripId)) {
    return errorResponse(400, "INVALID_TRIP_ID", "tripId must be a valid UUID.", requestId);
  }

  // Sensor features size check
  if (body.sensorFeatures) {
    const featuresStr = JSON.stringify(body.sensorFeatures);
    if (featuresStr.length > MAX_SENSOR_FEATURES_SIZE) {
      return errorResponse(400, "PAYLOAD_TOO_LARGE", "sensorFeatures exceeds 16 KB limit.", requestId);
    }
  }

  // ---- 3. Check idempotency (sync_receipts) ----
  const { data: existingReceipt } = await adminClient
    .from("sync_receipts")
    .select("id, result")
    .eq("user_id", user.id)
    .eq("operation_id", body.operationId)
    .maybeSingle();

  if (existingReceipt) {
    // Duplicate submission — return the original result
    return new Response(
      JSON.stringify({
        success: true,
        duplicate: true,
        ...existingReceipt.result,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  }

  // ---- 4. Validate device ownership ----
  if (body.deviceId) {
    const { data: device } = await adminClient
      .from("devices")
      .select("id, owner_id")
      .eq("id", body.deviceId)
      .maybeSingle();

    if (!device || device.owner_id !== user.id) {
      return errorResponse(403, "DEVICE_NOT_OWNED", "Device not registered to this user.", requestId);
    }
  }

  // ---- 5. Validate trip ownership ----
  if (body.tripId) {
    const { data: trip } = await adminClient
      .from("trips")
      .select("id, user_id")
      .eq("id", body.tripId)
      .maybeSingle();

    if (!trip || trip.user_id !== user.id) {
      return errorResponse(403, "TRIP_NOT_OWNED", "Trip not owned by this user.", requestId);
    }
  }

  // ---- 6. Find or create the shared hazard record ----
  // Look for an existing active hazard of the same type within the merge radius.
  const bbox = 0.0005; // ~55 m bounding box for initial filter (pre-Haversine)
  const { data: nearbyHazards, error: hazardQueryError } = await adminClient
    .from("hazards")
    .select("id, latitude, longitude, hazard_type, observation_count, severity")
    .eq("hazard_type", body.hazardType)
    .eq("is_active", true)
    .neq("verification_status", "REJECTED")
    .gte("latitude",  body.latitude  - bbox)
    .lte("latitude",  body.latitude  + bbox)
    .gte("longitude", body.longitude - bbox)
    .lte("longitude", body.longitude + bbox);

  if (hazardQueryError) {
    console.error("[ingest-hazard] Hazard query error:", hazardQueryError.message);
    return errorResponse(500, "DB_ERROR", "Database error during hazard lookup.", requestId);
  }

  // Find the closest hazard within the merge radius
  let matchedHazardId: string | null = null;
  let closestDistance = Infinity;

  for (const h of (nearbyHazards ?? [])) {
    const dist = haversineMeters(body.latitude, body.longitude, h.latitude, h.longitude);
    if (dist <= HAZARD_MERGE_RADIUS_METERS && dist < closestDistance) {
      closestDistance = dist;
      matchedHazardId = h.id;
    }
  }

  let hazardId: string;

  if (matchedHazardId) {
    // Update existing hazard's last_detected_at and upgrade severity if higher
    const severityOrder: Record<string, number> = { UNKNOWN: 0, LOW: 1, MEDIUM: 2, HIGH: 3 };
    const { data: existing } = await adminClient
      .from("hazards")
      .select("severity")
      .eq("id", matchedHazardId)
      .single();

    const newSeverityRank = severityOrder[body.severity] ?? 0;
    const existingSeverityRank = existing ? (severityOrder[existing.severity] ?? 0) : 0;

    const updatePayload: Record<string, unknown> = {
      last_detected_at: body.observedAt,
      updated_at: new Date().toISOString(),
    };
    if (newSeverityRank > existingSeverityRank) {
      updatePayload.severity = body.severity;
    }
    if (body.confidence !== undefined) {
      updatePayload.confidence = body.confidence;
    }

    await adminClient
      .from("hazards")
      .update(updatePayload)
      .eq("id", matchedHazardId);

    hazardId = matchedHazardId;
  } else {
    // Create a new hazard record (starts as PENDING)
    const { data: newHazard, error: insertError } = await adminClient
      .from("hazards")
      .insert({
        latitude: body.latitude,
        longitude: body.longitude,
        hazard_type: body.hazardType,
        severity: body.severity,
        verification_status: "PENDING",
        confidence: body.confidence ?? null,
        observation_count: 0, // will be incremented by trigger
        first_detected_at: body.observedAt,
        last_detected_at: body.observedAt,
        source: body.source ?? "SENSOR",
        created_by: user.id,
        is_active: true,
      })
      .select("id")
      .single();

    if (insertError || !newHazard) {
      console.error("[ingest-hazard] Hazard insert error:", insertError?.message);
      return errorResponse(500, "DB_ERROR", "Failed to create hazard record.", requestId);
    }

    hazardId = newHazard.id;
  }

  // ---- 7. Create hazard_observation ----
  const { data: obs, error: obsError } = await adminClient
    .from("hazard_observations")
    .insert({
      hazard_id: hazardId,
      user_id: user.id,
      device_id: body.deviceId ?? null,
      trip_id: body.tripId ?? null,
      latitude: body.latitude,
      longitude: body.longitude,
      hazard_type: body.hazardType,
      severity: body.severity,
      confidence: body.confidence ?? null,
      observed_at: body.observedAt,
      source: body.source ?? "SENSOR",
      event_fingerprint: body.eventFingerprint,
      sensor_features: body.sensorFeatures ?? null,
    })
    .select("id")
    .single();

  if (obsError) {
    // Handle unique constraint violation (duplicate fingerprint)
    if (obsError.code === "23505") {
      return errorResponse(409, "DUPLICATE_OBSERVATION", "Observation already recorded.", requestId);
    }
    console.error("[ingest-hazard] Observation insert error:", obsError.message);
    return errorResponse(500, "DB_ERROR", "Failed to create observation record.", requestId);
  }

  // ---- 8. Record sync receipt ----
  const receiptResult = {
    hazardId,
    observationId: obs!.id,
    isNewHazard: !matchedHazardId,
  };

  await adminClient
    .from("sync_receipts")
    .insert({
      user_id: user.id,
      operation_id: body.operationId,
      operation_type: "HAZARD_OBSERVATION",
      processed_at: new Date().toISOString(),
      result: receiptResult,
    });

  // ---- 9. Return success ----
  return new Response(
    JSON.stringify({
      success: true,
      duplicate: false,
      hazardId,
      observationId: obs!.id,
      isNewHazard: !matchedHazardId,
    }),
    { status: 201, headers: { "Content-Type": "application/json" } }
  );
});
