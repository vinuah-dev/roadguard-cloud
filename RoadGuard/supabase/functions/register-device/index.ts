// ============================================================
// Edge Function: register-device/index.ts
// Purpose: Register or update an ESP32 device for the
//          authenticated user. Validates identifier format
//          and prevents cross-user registration hijacking.
// ============================================================

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.7";

interface RegisterDeviceRequest {
  deviceName: string;
  deviceIdentifier: string;
  firmwareVersion?: string;
}

serve(async (req: Request) => {
  const requestId = crypto.randomUUID();

  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ error: "Only POST is allowed.", code: "METHOD_NOT_ALLOWED" }),
      { status: 405, headers: { "Content-Type": "application/json" } }
    );
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return new Response(
      JSON.stringify({ error: "Authorization header required.", code: "MISSING_AUTH", requestId }),
      { status: 401, headers: { "Content-Type": "application/json" } }
    );
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const serviceKey  = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const anonKey     = Deno.env.get("SUPABASE_ANON_KEY")!;

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false },
  });

  const { data: { user }, error: authError } = await userClient.auth.getUser();
  if (authError || !user) {
    return new Response(
      JSON.stringify({ error: "Invalid or expired token.", code: "INVALID_TOKEN", requestId }),
      { status: 401, headers: { "Content-Type": "application/json" } }
    );
  }

  let body: RegisterDeviceRequest;
  try {
    body = await req.json();
  } catch {
    return new Response(
      JSON.stringify({ error: "Request body must be valid JSON.", code: "INVALID_JSON", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  // Validation
  if (!body.deviceName || body.deviceName.length < 1 || body.deviceName.length > 100) {
    return new Response(
      JSON.stringify({ error: "deviceName must be 1–100 chars.", code: "INVALID_DEVICE_NAME", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const identifierRegex = /^[A-Za-z0-9_\-:\.]{3,64}$/;
  if (!body.deviceIdentifier || !identifierRegex.test(body.deviceIdentifier)) {
    return new Response(
      JSON.stringify({
        error: "deviceIdentifier must be 3–64 chars, alphanumeric with - _ : .",
        code: "INVALID_DEVICE_IDENTIFIER",
        requestId,
      }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const adminClient = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false },
  });

  // Upsert device (create or update for same owner+identifier)
  const { data: device, error: upsertError } = await adminClient
    .from("devices")
    .upsert(
      {
        owner_id: user.id,
        device_name: body.deviceName,
        device_identifier: body.deviceIdentifier,
        firmware_version: body.firmwareVersion ?? null,
        status: "ACTIVE",
        last_seen_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      },
      { onConflict: "owner_id,device_identifier" }
    )
    .select("id, device_name, device_identifier, status, firmware_version, created_at")
    .single();

  if (upsertError || !device) {
    console.error("[register-device] Upsert error:", upsertError?.message);
    return new Response(
      JSON.stringify({ error: "Failed to register device.", code: "DB_ERROR", requestId }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }

  return new Response(
    JSON.stringify({ success: true, device }),
    { status: 200, headers: { "Content-Type": "application/json" } }
  );
});
