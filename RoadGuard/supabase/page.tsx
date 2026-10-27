import { createClient } from "@/utils/supabase/server";

export default async function Page() {
  const supabase = await createClient();

  const { data: hazards, error } = await supabase
    .from("hazards")
    .select("id, hazard_type, latitude, longitude, severity, verification_status, observation_count")
    .eq("is_active", true)
    .order("last_detected_at", { ascending: false })
    .limit(20);

  if (error) {
    return (
      <div style={{ color: "red", padding: "1rem" }}>
        <strong>Supabase Error:</strong> {error.message}
      </div>
    );
  }

  return (
    <div style={{ padding: "1rem", fontFamily: "monospace" }}>
      <h1>RoadGuard — Live Hazards ({hazards?.length ?? 0})</h1>
      <ul>
        {hazards?.map((h) => (
          <li key={h.id}>
            [{h.hazard_type}] {h.severity} — lat: {h.latitude}, lon: {h.longitude} —{" "}
            {h.verification_status} ({h.observation_count} obs)
          </li>
        ))}
      </ul>
    </div>
  );
}
