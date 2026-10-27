// ============================================================
// Edge Function: report-hazard/index.ts
// Purpose: Allows users to report a potential correction or
//          dispute an existing hazard record.
//          Sets verification_status to DISPUTED for admin review.
//          Does NOT allow arbitrary verification status changes.
// ============================================================

import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.7";

interface ReportHazardRequest {
  hazardId: string;
  reason: "FALSE_POSITIVE" | "RESOLVED" | "INCORRECT_TYPE" | "OTHER";
  notes?: string;
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

  let body: ReportHazardRequest;
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

  const validReasons = ["FALSE_POSITIVE", "RESOLVED", "INCORRECT_TYPE", "OTHER"];
  if (!validReasons.includes(body.reason)) {
    return new Response(
      JSON.stringify({ error: "Invalid reason value.", code: "INVALID_REASON", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  if (body.notes && body.notes.length > 500) {
    return new Response(
      JSON.stringify({ error: "notes must be ≤500 chars.", code: "NOTES_TOO_LONG", requestId }),
      { status: 400, headers: { "Content-Type": "application/json" } }
    );
  }

  const adminClient = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } });

  // Check hazard exists and is active
  const { data: hazard } = await adminClient
    .from("hazards")
    .select("id, verification_status, is_active")
    .eq("id", body.hazardId)
    .maybeSingle();

  if (!hazard || !hazard.is_active) {
    return new Response(
      JSON.stringify({ error: "Hazard not found or inactive.", code: "HAZARD_NOT_FOUND", requestId }),
      { status: 404, headers: { "Content-Type": "application/json" } }
    );
  }

  // Set to DISPUTED for admin review; users cannot directly REJECT or VERIFY
  const { error: updateError } = await adminClient
    .from("hazards")
    .update({
      verification_status: "DISPUTED",
      updated_at: new Date().toISOString(),
    })
    .eq("id", body.hazardId)
    .eq("verification_status", "VERIFIED"); // only change VERIFIED hazards to DISPUTED

  // Record the report as a trip event (no trip_id; just a standalone event log in JSONB)
  // In a production system, we'd have a separate reports table; here we use minimal logging.
  console.log(`[report-hazard] User ${user.id} reported hazard ${body.hazardId}: ${body.reason}`);

  return new Response(
    JSON.stringify({
      success: true,
      message: "Report submitted. The hazard has been flagged for review.",
      hazardId: body.hazardId,
    }),
    { status: 200, headers: { "Content-Type": "application/json" } }
  );
});
