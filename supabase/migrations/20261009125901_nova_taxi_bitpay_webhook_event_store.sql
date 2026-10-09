create table if not exists public.nova_taxi_bitpay_webhook_events (
  event_id text primary key,
  payment_attempt_id uuid references public.nova_taxi_payment_attempts(id) on delete restrict,
  event_type text not null,
  provider_payment_id text,
  livemode boolean not null default false,
  received_at timestamptz not null default now(),
  processed_at timestamptz
);
alter table public.nova_taxi_bitpay_webhook_events enable row level security;
revoke all on public.nova_taxi_bitpay_webhook_events from public, anon, authenticated;
grant all on public.nova_taxi_bitpay_webhook_events to service_role;