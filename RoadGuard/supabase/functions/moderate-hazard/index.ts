// ============================================================
// Edge Function: moderate-hazard/index.ts
// Purpose: Administrative moderation endpoint.
//          Allows trusted administrators to VERIFY, REJECT, or
//          DEACTIVATE hazard records. Requires the caller to
//          have the 'admin' role set in their JWT app_metadata.
//
// Normal users cannot call this endpoint — the JWT role check
// rejects requests from standard authenticated accounts.
// Set admin role via Supabase Admin API (never client-side).
// ============================================================

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.7";

interface ModerateHazardRequest {
  hazardId: string;
  action: "VERIFY" | "REJECT" | "DEACTIVATE" | "REACTIVATE";
  reason?: string;
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
      JSON.stringify({ error: "Authorization required.", code: "MISSING_AUTH", requestId }),
      { status: 401, headers: { "Content-Type": "application/json" } }
    );
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const serviceKey  = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const anonKey     = Deno.env.get("SUPABASE_ANON_KEY")!;

  // Verify JWT
  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false },
  });
  const { data: { user }, error: authError } = await userClient.auth.getUser();
  if (authError || !user) {
    return new Response(
      JSON.stringify({ error: "Invalid token.", code: "INVALID_TOKEN", requestId }),
      { status: 401, headers: { "Content-Type": "application/json" } }
    );
  }

  // Check admin role in app_metadata (set via Supabase Admin API — never by clients)
  const appMeta = user.app_metadata ?? {};
  if (appMeta.role !== "admin") {
    return new Response(
      JSON.stringify({ error: "Admin access required.", code: "FORBIDDEN", requestId }),
      { status: 403, headers: { "Content-Type": "application/json" } }
    );
  }

  let body: ModerateHazardRequest;
  try { body = await req.json(); } catch {
    return new Response(
      JSON.stringify({ error: "Invalid JSON.", code: "INVALID_JSON", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (!body.hazardId || !UUID_REGEX.test(body.hazardId)) {
    return new Response(
      JSON.stringify({ error: "hazardId must be a valid UUID.", code: "INVALID_HAZARD_ID", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const validActions = ["VERIFY", "REJECT", "DEACTIVATE", "REACTIVATE"];
  if (!validActions.includes(body.action)) {
    return new Response(
      JSON.stringify({ error: "Invalid action.", code: "INVALID_ACTION", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const adminClient = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } });

  const updatePayload: Record<string, unknown> = {
    updated_at: new Date().toISOString(),
  };

  switch (body.action) {
    case "VERIFY":
      updatePayload.verification_status = "VERIFIED";
      updatePayload.is_active = true;
      break;
    case "REJECT":
      updatePayload.verification_status = "REJECTED";
      // Keep is_active true for historical reference; app queries exclude REJECTED
      break;
    case "DEACTIVATE":
      updatePayload.is_active = false;
      break;
    case "REACTIVATE":
      updatePayload.is_active = true;
      updatePayload.verification_status = "PENDING";
      break;
  }

  const { error: updateError } = await adminClient
    .from("hazards")
    .update(updatePayload)
    .eq("id", body.hazardId);

  if (updateError) {
    console.error("[moderate-hazard] Update error:", updateError.message);
    return new Response(
      JSON.stringify({ error: "Failed to update hazard.", code: "DB_ERROR", requestId }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    );
  }

  console.log(`[moderate-hazard] Admin ${user.id} performed ${body.action} on hazard ${body.hazardId}`);

  return new Response(
    JSON.stringify({ success: true, hazardId: body.hazardId, action: body.action }),
    { status: 200, headers: { "Content-Type": "application/json" } }
  );
});
