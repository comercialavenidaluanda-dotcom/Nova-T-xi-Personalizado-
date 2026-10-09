import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const response = (body: unknown, status=200) => new Response(JSON.stringify(body), {status, headers:{"Content-Type":"application/json"}});
const hex = (b:ArrayBuffer) => Array.from(new Uint8Array(b), x=>x.toString(16).padStart(2,"0")).join("");
function equal(a:string,b:string){if(a.length!==b.length)return false;let d=0;for(let i=0;i<a.length;i++)d|=a.charCodeAt(i)^b.charCodeAt(i);return d===0;}
async function verify(raw:string,header:string|null,secret:string){
 const m=/^t=(\d+),v1=([0-9a-f]{64})$/.exec(header??""); if(!m)return false;
 if(Math.abs(Date.now()/1000-Number(m[1]))>600)return false;
 const key=await crypto.subtle.importKey("raw",new TextEncoder().encode(secret),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
 const sig=await crypto.subtle.sign("HMAC",key,new TextEncoder().encode(m[1]+"."+raw));
 return equal(hex(sig),m[2]);
}
Deno.serve(async(req:Request)=>{
 if(req.method!=="POST")return response({error:"method_not_allowed"},405);
 const secret=Deno.env.get("BITPAY_WHSEC"), url=Deno.env.get("SUPABASE_URL"), service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
 if(!secret||!url||!service)return response({error:"webhook_not_configured"},503);
 const raw=await req.text(); if(!await verify(raw,req.headers.get("BitPay-Signature"),secret))return response({error:"invalid_signature"},400);
 let evt:any;try{evt=JSON.parse(raw)}catch{return response({error:"invalid_json"},400)}
 const headerId=req.headers.get("BitPay-Event-Id"), eventId=headerId??evt?.id;
 if(!eventId||(headerId&&evt?.id&&headerId!==evt.id)||typeof evt?.type!=="string")return response({error:"invalid_event_identity"},400);
 const db=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
 const providerId=evt?.data?.id??null;
 const {data:attempt}=providerId?await db.from("nova_taxi_payment_attempts").select("id,corrida_id").eq("prestador_pagamento_id",providerId).maybeSingle():{data:null};
 const {error:ins}=await db.from("nova_taxi_bitpay_webhook_events").insert({event_id:eventId,payment_attempt_id:attempt?.id??null,event_type:evt.type,provider_payment_id:providerId,livemode:evt.livemode===true});
 if(ins?.code==="23505")return response({received:true,duplicate:true});
 if(ins)return response({error:"event_persistence_failed"},500);
 if(!attempt){await db.from("nova_taxi_bitpay_webhook_events").update({processed_at:new Date().toISOString()}).eq("event_id",eventId);return response({received:true,matched:false})}
 const status=evt?.data?.status;let state:string|null=null;
 if(evt.type==="payment.succeeded"&&status==="SUCCEEDED")state="SUCCEEDED";
 else if(evt.type==="payment.failed")state="FAILED";
 else if(evt.type==="payment.unknown")state="UNKNOWN";
 else if(evt.type==="payment.expired")state="EXPIRED";
 else if(evt.type==="payment.cancelled")state="CANCELLED";
 else if(evt.type==="payment.created")state="PENDING";
 else if(status==="PROCESSING")state="PROCESSING";
 if(state){
  const now=new Date().toISOString(), upd:any={estado:state,atualizado_em:now};
  if(state==="SUCCEEDED")upd.pago_em=now;
  if(evt?.data?.failure_code)upd.codigo_erro=String(evt.data.failure_code).slice(0,120);
  const {error}=await db.from("nova_taxi_payment_attempts").update(upd).eq("id",attempt.id);
  if(error)return response({error:"payment_state_update_failed"},500);
  const states:any={SUCCEEDED:"pago",FAILED:"falhado",UNKNOWN:"desconhecido",EXPIRED:"expirado",CANCELLED:"cancelado",PENDING:"pendente",PROCESSING:"em_processamento"};
  const summary:any={estado:states[state],prestador_pagamento_id:providerId};if(state==="SUCCEEDED")summary.pago_em=now;
  await db.from("nova_taxi_payments").update(summary).eq("corrida_id",attempt.corrida_id);
 }
 await db.from("nova_taxi_bitpay_webhook_events").update({processed_at:new Date().toISOString()}).eq("event_id",eventId);
 return response({received:true,processed:true});
});