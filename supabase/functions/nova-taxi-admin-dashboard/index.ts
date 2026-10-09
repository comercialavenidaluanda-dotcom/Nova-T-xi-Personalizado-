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
  // A tabela privada não está exposta ao PostgREST. Validamos o JWT do utilizador
  // através da função RPC pública já existente, que consulta a tabela privada em SQL.
  const caller = createClient(
    url,
    Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "sb_publishable_qCs2fRvNoGJhopd2LDom6Q_qL2AJbwp",
    {
      global: { headers: { Authorization: `Bearer ${token}` } },
      auth: { persistSession: false, autoRefreshToken: false },
    },
  );
  const { data: isAdmin, error: grantError } = await caller.rpc("nova_taxi_admin_is_admin");

  if (grantError) return reply(500, { error: "ADMIN_AUTH_CHECK_FAILED" });
  if (isAdmin !== true) return reply(403, { error: "ADMIN_REQUIRED" });

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
      const [rides, profiles, locations, payments, safety, ridesForCharts, paymentsForCharts] = await Promise.all([
        ridesQuery(), profilesQuery(),
        admin.from("nova_taxi_driver_locations").select("motorista_id,latitude,longitude,accuracy_m,captured_at,updated_at").order("updated_at", { ascending: false }).limit(100),
        admin.from("nova_taxi_payments").select("id", { count: "exact", head: true }),
        admin.from("nova_taxi_sos_events").select("id", { count: "exact", head: true }),
        admin.from("nova_taxi_rides").select("id,estado,criado_em,valor_final").gte("criado_em", new Date(Date.now()-13*86400000).toISOString()).order("criado_em", { ascending: false }).limit(1000),
        admin.from("nova_taxi_payments").select("id,valor,estado,metodo,criado_em").gte("criado_em", new Date(Date.now()-13*86400000).toISOString()).order("criado_em", { ascending: false }).limit(1000),
      ]);
      if (locations.error || payments.error || safety.error || ridesForCharts.error || paymentsForCharts.error) throw locations.error ?? payments.error ?? safety.error ?? ridesForCharts.error ?? paymentsForCharts.error;
      const dayKey = (value: string) => value.slice(0,10);
      const dayLabels = Array.from({length:14},(_,i)=>{const d=new Date();d.setHours(12,0,0,0);d.setDate(d.getDate()-(13-i));return {key:d.toISOString().slice(0,10),label:d.toLocaleDateString("pt-AO",{day:"2-digit",month:"2-digit"})};});
      const rideDayCounts = new Map<string,number>();
      const revenueDayTotals = new Map<string,number>();
      const statusTotals = new Map<string,number>();
      const paymentMethodTotals = new Map<string,number>();
      for (const r of ridesForCharts.data ?? []) {
        const k=dayKey(r.criado_em); rideDayCounts.set(k,(rideDayCounts.get(k)??0)+1);
        const status=String(r.estado??"Sem estado");statusTotals.set(status,(statusTotals.get(status)??0)+1);
      }
      for (const p of paymentsForCharts.data ?? []) {
        const method=String(p.metodo??"Não indicado");paymentMethodTotals.set(method,(paymentMethodTotals.get(method)??0)+1);
        if (["pago","paid","concluido","concluído","success","succeeded"].includes(String(p.estado??"").toLowerCase())) {
          const k=dayKey(p.criado_em);revenueDayTotals.set(k,(revenueDayTotals.get(k)??0)+Number(p.valor??0));
        }
      }
      const analytics = {
        daily_rides: dayLabels.map(d=>({label:d.label,value:rideDayCounts.get(d.key)??0})),
        daily_revenue: dayLabels.map(d=>({label:d.label,value:revenueDayTotals.get(d.key)??0})),
        statuses: Array.from(statusTotals,([label,value])=>({label:label.slice(0,12),value})).sort((a,b)=>b.value-a.value).slice(0,8),
        payment_methods: Array.from(paymentMethodTotals,([label,value])=>({label:label.slice(0,12),value})).sort((a,b)=>b.value-a.value).slice(0,8),
      };
      return reply(200, {
        counts: {
          rides: rides.length,
          drivers: profiles.filter((p: any) => p.role === "motorista").length,
          payments: payments.count ?? 0,
          safety: safety.count ?? 0,
        },
        analytics,
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

    if (section === "fleet") {
      const [fleets, members, vehicles, geofences, alerts, maintenance, subscriptions] = await Promise.all([
        admin.from("nova_taxi_fleets").select("id,name,legal_name,tax_number,owner_user_id,status,created_at,updated_at").order("created_at", { ascending: false }).limit(limit),
        admin.from("nova_taxi_fleet_members").select("id,fleet_id,user_id,role,status,created_at").order("created_at", { ascending: false }).limit(limit),
        admin.from("nova_taxi_fleet_vehicles").select("id,fleet_id,taxi_vehicle_id,plate,make,model,status,current_driver_id,odometer_km,created_at,updated_at").order("created_at", { ascending: false }).limit(limit),
        admin.from("nova_taxi_geofences").select("id,fleet_id,name,center_lat,center_lng,radius_m,enabled,rules,created_at,updated_at").order("created_at", { ascending: false }).limit(limit),
        admin.from("nova_taxi_fleet_security_alerts").select("id,fleet_id,fleet_vehicle_id,driver_id,alert_type,severity,status,latitude,longitude,details,detected_at").order("detected_at", { ascending: false }).limit(limit),
        admin.from("nova_taxi_fleet_maintenance").select("id,fleet_id,fleet_vehicle_id,category,description,due_at,due_odometer_km,cost_aoa,status,provider_name,completed_at,created_at").order("created_at", { ascending: false }).limit(limit),
        admin.from("nova_taxi_fleet_subscriptions").select("id,fleet_id,plan_code,status,monthly_price_aoa,commission_percent,starts_at,renews_at,created_at").order("created_at", { ascending: false }).limit(limit),
      ]);
      const failure = fleets.error ?? members.error ?? vehicles.error ?? geofences.error ?? alerts.error ?? maintenance.error ?? subscriptions.error;
      if (failure) throw failure;
      return reply(200, {
        fleets: fleets.data ?? [], members: members.data ?? [], vehicles: vehicles.data ?? [],
        geofences: geofences.data ?? [], alerts: alerts.data ?? [], maintenance: maintenance.data ?? [],
        subscriptions: subscriptions.data ?? [],
      });
    }

    if (section === "partners") {
      const { data, error } = await admin.from("empresas")
        .select("id,nome,categoria,provincia,telefone,email,website,destaque,criado_em")
        .order("criado_em", { ascending: false }).limit(limit);
      if (error) throw error;
      return reply(200, { data: data ?? [] });
    }

    if (section === "express") {
      const [requests, deliveries, serviceTypes] = await Promise.all([
        admin.from("nova_taxi_service_requests").select("id,utilizador_id,tipo_servico_id,origem_texto,origem_lat,origem_lng,destino_texto,destino_lat,destino_lng,estado,observacoes,criado_em,atualizado_em").order("criado_em", { ascending: false }).limit(limit),
        admin.from("nova_taxi_delivery_orders").select("id,solicitante_id,motorista_id,origem_texto,origem_lat,origem_lng,destino_texto,destino_lat,destino_lng,nome_remetente,telefone_remetente,nome_destinatario,telefone_destinatario,descricao_encomenda,estado,valor_estimado,valor_final,criado_em,atualizado_em").order("criado_em", { ascending: false }).limit(limit),
        admin.from("nova_taxi_service_types").select("id,codigo,nome,descricao,ativo,criado_em").order("criado_em", { ascending: true }).limit(limit),
      ]);
      const failure = requests.error ?? deliveries.error ?? serviceTypes.error;
      if (failure) throw failure;
      return reply(200, { requests: requests.data ?? [], deliveries: deliveries.data ?? [], service_types: serviceTypes.data ?? [] });
    }

    if (section === "audit") return reply(200, { data: [] });
    return reply(400, { error: "UNKNOWN_SECTION" });
  } catch (error) {
    console.error("admin dashboard query failed", error);
    return reply(500, { error: "ADMIN_DATA_QUERY_FAILED" });
  }
});
