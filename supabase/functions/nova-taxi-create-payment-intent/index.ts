import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const cors={"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info","Access-Control-Allow-Methods":"POST, OPTIONS"};
const out=(v:unknown,s=200)=>new Response(JSON.stringify(v),{status:s,headers:{...cors,"Content-Type":"application/json"}});
Deno.serve(async(req:Request)=>{
 if(req.method==="OPTIONS")return new Response("ok",{headers:cors});
 if(req.method!=="POST")return out({error:"method_not_allowed"},405);
 const auth=req.headers.get("Authorization");if(!auth?.startsWith("Bearer "))return out({error:"authentication_required"},401);
 const url=Deno.env.get("SUPABASE_URL"),anon=Deno.env.get("SUPABASE_ANON_KEY"),service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),sk=Deno.env.get("BITPAY_SK");
 if(!url||!anon||!service)return out({error:"backend_not_configured"},503);
 if(!sk)return out({error:"bitpay_sandbox_key_not_configured"},503);
 const userDb=createClient(url,anon,{global:{headers:{Authorization:auth}},auth:{persistSession:false,autoRefreshToken:false}});
 const db=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
 const {data:u,error:ue}=await userDb.auth.getUser(auth.slice(7));if(ue||!u.user)return out({error:"invalid_session"},401);
 let b:any;try{b=await req.json()}catch{return out({error:"invalid_json"},400)}
 const rideId=String(b?.ride_id??"").trim(),method=String(b?.payment_method??"");
 if(!rideId||!["multicaixa_express","multicaixa_reference"].includes(method))return out({error:"unsupported_method",supported_methods:["multicaixa_express","multicaixa_reference"]},422);
 const {data:ride,error:re}=await db.from("nova_taxi_rides").select("id,passageiro_id,estado,valor_final").eq("id",rideId).maybeSingle();
 if(re)return out({error:"ride_lookup_failed"},500);
 if(!ride||ride.passageiro_id!==u.user.id)return out({error:"ride_not_found"},404);
 if(ride.estado!=="concluida")return out({error:"ride_not_completed"},409);
 const amount=Number(ride.valor_final);if(!Number.isSafeInteger(amount)||amount<=0)return out({error:"invalid_final_amount"},409);
 let mobile:string|undefined;
 if(method==="multicaixa_express"){mobile=String(b?.mobile??"").replace(/\D/g,"");if(!/^9\d{8}$/.test(mobile))return out({error:"valid_angolan_mobile_required"},422)}
 const {data:active,error:ae}=await db.from("nova_taxi_payment_attempts").select("id,metodo,valor,estado,prestador_pagamento_id,entidade_referencia,numero_referencia,expira_em").eq("corrida_id",rideId).in("estado",["PENDING","PROCESSING","UNKNOWN"]).maybeSingle();
 if(ae)return out({error:"payment_lookup_failed"},500);
 if(active){if(active.metodo!==method)return out({error:"active_payment_exists",payment:active},409);return out({payment:active,reused:true,confirmed:false})}
 const {data:old}=await db.from("nova_taxi_payments").select("estado").eq("corrida_id",rideId).maybeSingle();
 if(old?.estado==="pago")return out({error:"ride_already_paid"},409);
 const id=crypto.randomUUID();
 const {error:ie}=await db.from("nova_taxi_payment_attempts").insert({id,corrida_id:rideId,pagador_id:u.user.id,metodo:method,valor:amount,moeda:"AOA",estado:"PENDING",prestador:"bitpay",chave_idempotencia:id});
 if(ie)return out({error:ie.code==="23505"?"active_payment_exists":"attempt_create_failed"},ie.code==="23505"?409:500);
 const {error:preSummaryError}=await db.from("nova_taxi_payments").upsert({corrida_id:rideId,metodo,valor:amount,estado:"pendente",prestador:"bitpay",moeda:"AOA",chave_idempotencia:id},{onConflict:"corrida_id"});
 if(preSummaryError){await db.from("nova_taxi_payment_attempts").update({estado:"FAILED",codigo_erro:"summary_failed"}).eq("id",id);return out({error:"summary_failed"},500)}
 const payload:any={amount,currency:"AOA",payment_method:method,merchant_reference:("NOVA-"+rideId).slice(0,64),metadata:{nova_ride_id:rideId,nova_attempt_id:id}};
 if(mobile)payload.customer={mobile};
 let res:Response;
 try{res=await fetch("https://api-sandbox.bitpay.ao/v1/payment_intents",{method:"POST",headers:{Authorization:"Bearer "+sk,"Content-Type":"application/json","Idempotency-Key":id},body:JSON.stringify(payload),signal:AbortSignal.timeout(12000)})}
 catch{await db.from("nova_taxi_payment_attempts").update({estado:"UNKNOWN",codigo_erro:"provider_timeout_or_network_error",atualizado_em:new Date().toISOString()}).eq("id",id).in("estado",["PENDING","PROCESSING"]);const {data:latest}=await db.from("nova_taxi_payment_attempts").select("estado").eq("id",id).single();if(latest?.estado==="UNKNOWN")await db.from("nova_taxi_payments").update({estado:"desconhecido"}).eq("corrida_id",rideId).eq("estado","pendente");return out({error:"payment_status_unknown",attempt_id:id,retry_same_attempt:true},202)}
 const data:any=await res.json().catch(()=>({}));
 if(!res.ok||!data?.id){const code=String(data?.error?.code??"provider_request_failed");await db.from("nova_taxi_payment_attempts").update({estado:"FAILED",codigo_erro:code.slice(0,120),atualizado_em:new Date().toISOString()}).eq("id",id).in("estado",["PENDING","PROCESSING","UNKNOWN"]);await db.from("nova_taxi_payments").update({estado:"falhado"}).eq("corrida_id",rideId).eq("estado","pendente");return out({error:"bitpay_request_failed",code},502)}
 const ref=data.reference??{},expiry=ref.expires_at??null;
 const {data:saved,error:se}=await db.from("nova_taxi_payment_attempts").update({prestador_pagamento_id:data.id,entidade_referencia:ref.entity??null,numero_referencia:ref.number??null,expira_em:expiry,estado:data.status==="PROCESSING"?"PROCESSING":"PENDING",atualizado_em:new Date().toISOString()}).eq("id",id).in("estado",["PENDING","PROCESSING","UNKNOWN"]).select("id,metodo,valor,moeda,estado,prestador_pagamento_id,entidade_referencia,numero_referencia,expira_em").single();
 if(se||!saved)return out({error:"provider_created_local_state_unknown",attempt_id:id},202);
 const {error:summaryError}=await db.from("nova_taxi_payments").update({prestador:"bitpay",prestador_pagamento_id:data.id,entidade_referencia:ref.entity??null,numero_referencia:ref.number??null,expira_em:expiry,chave_idempotencia:id}).eq("corrida_id",rideId);
 if(summaryError)return out({payment:saved,warning:"summary_sync_pending",confirmed:false},202);
 return out({payment:saved,confirmed:false,environment:"sandbox"},201);
});