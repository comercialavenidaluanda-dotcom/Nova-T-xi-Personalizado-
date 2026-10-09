alter table public.nova_taxi_payment_attempts enable row level security;
revoke all on public.nova_taxi_payment_attempts from anon, authenticated;
grant select on public.nova_taxi_payment_attempts to authenticated;
grant all on public.nova_taxi_payment_attempts to service_role;

create policy nova_taxi_payment_attempts_select_participant
on public.nova_taxi_payment_attempts for select to authenticated
using (
  exists (select 1 from public.nova_taxi_rides r
    where r.id = corrida_id and
      (r.passageiro_id = (select auth.uid()) or r.motorista_id = (select auth.uid())))
  or (select private.nova_taxi_is_admin())
);

create unique index if not exists nova_taxi_payment_attempts_one_active_per_ride
on public.nova_taxi_payment_attempts(corrida_id)
where estado in ('PENDING','PROCESSING','UNKNOWN');

alter table public.nova_taxi_payments
  add column if not exists prestador text,
  add column if not exists prestador_pagamento_id text,
  add column if not exists entidade_referencia text,
  add column if not exists numero_referencia text,
  add column if not exists expira_em timestamptz,
  add column if not exists pago_em timestamptz,
  add column if not exists taxa_prestador integer not null default 0,
  add column if not exists moeda text not null default 'AOA',
  add column if not exists chave_idempotencia text;