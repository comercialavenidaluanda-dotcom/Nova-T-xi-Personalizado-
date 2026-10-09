alter table public.nova_taxi_payment_attempts enable row level security;
revoke all on public.nova_taxi_payment_attempts from anon, authenticated;
grant select on public.nova_taxi_payment_attempts to authenticated;
grant all on public.nova_taxi_payment_attempts to service_role;

drop policy if exists nova_taxi_payment_attempts_select_participant on public.nova_taxi_payment_attempts;
create policy nova_taxi_payment_attempts_select_participant
on public.nova_taxi_payment_attempts for select to authenticated
using (
  exists (
    select 1 from public.nova_taxi_rides r
    where r.id = corrida_id
      and (r.passageiro_id = (select auth.uid()) or r.motorista_id = (select auth.uid()))
  )
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
  add column if not exists taxa_prestador integer not null default 0 check (taxa_prestador >= 0),
  add column if not exists moeda text not null default 'AOA' check (moeda = 'AOA'),
  add column if not exists chave_idempotencia text;

do $$
declare c record;
begin
  for c in select conname from pg_constraint
    where conrelid = 'public.nova_taxi_payments'::regclass
      and contype = 'c' and pg_get_constraintdef(oid) ilike '%metodo%'
  loop execute format('alter table public.nova_taxi_payments drop constraint %I', c.conname); end loop;
  for c in select conname from pg_constraint
    where conrelid = 'public.nova_taxi_payments'::regclass
      and contype = 'c' and pg_get_constraintdef(oid) ilike '%estado%'
  loop execute format('alter table public.nova_taxi_payments drop constraint %I', c.conname); end loop;
  for c in select conname from pg_constraint
    where conrelid = 'public.nova_taxi_rides'::regclass
      and contype = 'c' and pg_get_constraintdef(oid) ilike '%metodo_pagamento%'
  loop execute format('alter table public.nova_taxi_rides drop constraint %I', c.conname); end loop;
end $$;

alter table public.nova_taxi_payments
  add constraint nova_taxi_payments_metodo_allowed_check
  check (metodo in ('cash','kwik','multicaixa','multicaixa_express','multicaixa_reference','referencia','iban','transferencia'));
alter table public.nova_taxi_payments
  add constraint nova_taxi_payments_estado_allowed_check
  check (estado in ('pendente','em_processamento','pago','cancelado','falhado','desconhecido','expirado','reembolsado'));
alter table public.nova_taxi_rides
  add constraint nova_taxi_rides_metodo_pagamento_allowed_check
  check (metodo_pagamento is null or metodo_pagamento in ('cash','kwik','multicaixa','multicaixa_express','multicaixa_reference','referencia','iban','transferencia'));

create table if not exists private.nova_taxi_driver_settlements (
  id uuid primary key default gen_random_uuid(),
  corrida_id uuid not null unique references public.nova_taxi_rides(id) on delete restrict,
  motorista_id uuid not null references public.nova_taxi_driver_profiles(id) on delete restrict,
  valor_bruto integer not null check (valor_bruto >= 0),
  valor_comissao integer not null check (valor_comissao >= 0),
  valor_motorista integer not null check (valor_motorista >= 0),
  estado text not null default 'pendente' check (estado in ('pendente','aprovada','paga','falhada','cancelada')),
  metodo_desembolso text check (metodo_desembolso is null or metodo_desembolso in ('iban','kwik','manual')),
  referencia_desembolso text,
  criado_em timestamptz not null default now(),
  atualizado_em timestamptz not null default now(),
  pago_em timestamptz
);
alter table private.nova_taxi_driver_settlements enable row level security;
revoke all on private.nova_taxi_driver_settlements from public, anon, authenticated;
grant all on private.nova_taxi_driver_settlements to service_role;