import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
};

function reply(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: cors });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply(405, { error: "METHOD_NOT_ALLOWED" });

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return reply(500, { error: "SERVER_CONFIGURATION_ERROR" });

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : "";
  if (!token) return reply(401, { error: "AUTH_REQUIRED" });

  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userError } = await admin.auth.getUser(token);
  if (userError || !userData.user) return reply(401, { error: "SESSION_INVALID" });

  const userId = userData.user.id;
  const { data: grant, error: grantError } = await admin
    .schema("private")
    .from("nova_taxi_admins")
    .select("active")
    .eq("user_id", userId)
    .maybeSingle();

  if (grantError) return reply(500, { error: "ADMIN_AUTH_CHECK_FAILED" });
  if (!grant || grant.active !== true) return reply(403, { error: "ADMIN_REQUIRED" });

  let input: { section?: string; limit?: number } = {};
  try { input = await req.json(); } catch { /* empty request body is treated as overview */ }
  const section = input.section ?? "overview";
  const limit = Math.min(Math.max(Number(input.limit ?? 150), 1), 150);

  if (section === "validate") return reply(200, { authorized: true });

  const profilesQuery = async () => {
    const { data, error } = await admin.from("nova_taxi_profiles")
      .select("id,tipo_utilizador,nome,telefone,ativo,criado_em")
      .order("criado_em", { ascending: false }).limit(limit);
    if (error) throw error;
    return (data ?? []).map((p: any) => ({
      user_id: p.id, full_name: p.nome, phone: p.telefone, email: null,
      status: p.ativo ? "ACTIVE" : "INACTIVE", role: p.tipo_utilizador, created_at: p.criado_em,
    }));
  };
  const ridesQuery = async () => {
    const { data, error } = await admin.from("nova_taxi_rides")
      .select("id,passageiro_id,motorista_id,estado,categoria,origem_texto,destino_texto,valor_estimado,valor_final,metodo_pagamento,criado_em")
      .order("criado_em", { ascending: false }).limit(limit);
    if (error) throw error;
    return (data ?? []).map((r: any) => ({
      id: r.id, passenger_id: r.passageiro_id, driver_id: r.motorista_id,
      status: r.estado, service_category: r.categoria, pickup_address: r.origem_texto,
      destination_address: r.destino_texto, estimated_fare: r.valor_estimado,
      final_fare: r.valor_final, method: r.metodo_pagamento, created_at: r.criado_em,
    }));
  };

  try {
    if (section === "overview") {
      const [rides, profiles, locations, payments, safety] = await Promise.all([
        ridesQuery(), profilesQuery(),
        admin.from("nova_taxi_driver_locations").select("motorista_id,latitude,longitude,accuracy_m,captured_at,updated_at").order("updated_at", { ascending: false }).limit(100),
        admin.from("nova_taxi_payments").select("id", { count: "exact", head: true }),
        admin.from("nova_taxi_sos_events").select("id", { count: "exact", head: true }),
      ]);
      if (locations.error || payments.error || safety.error) throw locations.error ?? payments.error ?? safety.error;
      return reply(200, {
        counts: {
          rides: rides.length,
          drivers: profiles.filter((p: any) => p.role === "motorista").length,
          payments: payments.count ?? 0,
          safety: safety.count ?? 0,
        },
        rides: rides.slice(0, 10),
        profiles,
        locations: (locations.data ?? []).map((l: any) => ({
          driver_id: l.motorista_id, lat: l.latitude, lng: l.longitude,
          accuracy_m: l.accuracy_m, received_at: l.updated_at ?? l.captured_at,
          captured_at: l.captured_at, source: "nova_taxi_driver_locations", mock_location: false,
        })),
        server_time: new Date().toISOString(),
      });
    }

    if (section === "rides") return reply(200, { data: await ridesQuery() });

    if (section === "drivers") {
      const [profiles, driverResult, locations] = await Promise.all([
        profilesQuery(),
        admin.from("nova_taxi_driver_profiles").select("id,disponivel,aprovado,criado_em").order("criado_em", { ascending: false }).limit(limit),
        admin.from("nova_taxi_driver_locations").select("motorista_id,updated_at").order("updated_at", { ascending: false }).limit(limit),
      ]);
      if (driverResult.error || locations.error) throw driverResult.error ?? locations.error;
      const data = (driverResult.data ?? []).map((d: any) => ({
        user_id: d.id, verification_status: d.aprovado ? "Aprovado" : "Pendente",
        online: d.disponivel, rating: null, total_rides: null, last_location_at:
          locations.data?.find((l: any) => l.motorista_id === d.id)?.updated_at, created_at: d.criado_em,
      }));
      return reply(200, { data, profiles });
    }

    if (section === "passengers") {
      const profiles = await profilesQuery();
      return reply(200, { data: profiles.filter((p: any) => p.role === "passageiro") });
    }

    if (section === "payments") {
      const { data, error } = await admin.from("nova_taxi_payments")
        .select("id,corrida_id,valor,estado,metodo,prestador_pagamento_id,criado_em")
        .order("criado_em", { ascending: false }).limit(limit);
      if (error) throw error;
      return reply(200, { data: (data ?? []).map((p: any) => ({
        id: p.id, ride_id: p.corrida_id, amount: p.valor, platform_commission: null,
        driver_amount: null, method: p.metodo, status: p.estado,
        provider_reference: p.prestador_pagamento_id, created_at: p.criado_em,
      })) });
    }

    if (section === "commission") {
      const { data, error } = await admin.from("nova_taxi_commission_rules")
        .select("id,percentual,ativa,criado_em").order("criado_em", { ascending: false }).limit(limit);
      if (error) throw error;
      return reply(200, { data: (data ?? []).map((r: any) => ({
        category: "Geral", percentage: r.percentual, active: r.ativa, created_at: r.criado_em,
      })) });
    }

    if (section === "safety") {
      const { data, error } = await admin.from("nova_taxi_sos_events")
        .select("id,utilizador_id,corrida_id,latitude,longitude,estado,observacao,criado_em")
        .order("criado_em", { ascending: false }).limit(limit);
      if (error) throw error;
      return reply(200, { data: (data ?? []).map((s: any) => ({
        event_type: s.estado, severity: "A verificar", ride_id: s.corrida_id,
        actor_id: s.utilizador_id, lat: s.latitude, lng: s.longitude,
        details: { observation: s.observacao }, created_at: s.criado_em,
      })) });
    }

    if (section === "gps") {
      const [locations, profiles] = await Promise.all([
        admin.from("nova_taxi_driver_locations").select("motorista_id,latitude,longitude,accuracy_m,heading,captured_at,updated_at").order("updated_at", { ascending: false }).limit(limit),
        profilesQuery(),
      ]);
      if (locations.error) throw locations.error;
      return reply(200, {
        locations: (locations.data ?? []).map((l: any) => ({
          driver_id: l.motorista_id, lat: l.latitude, lng: l.longitude,
          accuracy_m: l.accuracy_m, heading: l.heading, received_at: l.updated_at,
          captured_at: l.captured_at, source: "nova_taxi_driver_locations", mock_location: false,
        })),
        profiles,
      });
    }

    if (section === "audit") return reply(200, { data: [] });
    return reply(400, { error: "UNKNOWN_SECTION" });
  } catch (error) {
    console.error("admin dashboard query failed", error);
    return reply(500, { error: "ADMIN_DATA_QUERY_FAILED" });
  }
});
