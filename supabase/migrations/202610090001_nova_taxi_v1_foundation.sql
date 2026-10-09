-- NOVA Taxi V1 foundation. Review before applying to Supabase.
-- Isolated versioned namespace: does not alter or delete legacy NOVA Taxi tables.
begin;

create extension if not exists pgcrypto;

create table if not exists public.nova_taxi_v1_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  full_name text not null check (length(trim(full_name)) between 2 and 120),
  phone text,
  user_type text not null check (user_type in ('passenger','driver')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_driver_profiles (
  user_id uuid primary key references public.nova_taxi_v1_profiles(id) on delete cascade,
  approval_status text not null default 'pending' check (approval_status in ('pending','approved','rejected','suspended')),
  is_online boolean not null default false,
  verification_notes text,
  approved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_vehicles (
  id uuid primary key default gen_random_uuid(),
  driver_id uuid not null references public.nova_taxi_v1_driver_profiles(user_id) on delete cascade,
  make text not null,
  model text not null,
  plate text not null,
  color text,
  category text not null check (category in ('cool','executivo')),
  active boolean not null default false,
  verified_at timestamptz,
  created_at timestamptz not null default now(),
  unique (plate)
);

create table if not exists public.nova_taxi_v1_fare_rules (
  id uuid primary key default gen_random_uuid(),
  category text not null check (category in ('cool','executivo')),
  zone_code text not null default 'LUANDA',
  currency text not null default 'AOA' check (currency = 'AOA'),
  minimum_fare numeric(12,2) not null check (minimum_fare >= 0),
  included_km numeric(8,2) not null default 2.5 check (included_km >= 0),
  included_minutes numeric(8,2) not null default 6 check (included_minutes >= 0),
  extra_km_price numeric(12,2) not null check (extra_km_price >= 0),
  extra_minute_price numeric(12,2) not null check (extra_minute_price >= 0),
  active boolean not null default false,
  valid_from timestamptz not null default now(),
  created_at timestamptz not null default now(),
  unique (category, zone_code, valid_from)
);

create table if not exists public.nova_taxi_v1_commission_rules (
  id uuid primary key default gen_random_uuid(),
  percentage numeric(5,2) not null check (percentage >= 0 and percentage <= 100),
  active boolean not null default false,
  valid_from timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_rides (
  id uuid primary key default gen_random_uuid(),
  passenger_id uuid not null references public.nova_taxi_v1_profiles(id),
  driver_id uuid references public.nova_taxi_v1_driver_profiles(user_id),
  category text not null check (category in ('cool','executivo')),
  origin_label text not null,
  origin_lat double precision not null check (origin_lat between -90 and 90),
  origin_lng double precision not null check (origin_lng between -180 and 180),
  destination_label text not null,
  destination_lat double precision not null check (destination_lat between -90 and 90),
  destination_lng double precision not null check (destination_lng between -180 and 180),
  payment_method text not null check (payment_method in ('cash','multicaixa_express','reference')),
  status text not null default 'requested' check (status in ('requested','accepted','driver_arriving','driver_arrived','in_trip','completed','cancelled')),
  estimated_fare numeric(12,2) not null check (estimated_fare >= 0),
  final_fare numeric(12,2) check (final_fare >= 0),
  commission_amount numeric(12,2) check (commission_amount >= 0),
  driver_amount numeric(12,2) check (driver_amount >= 0),
  start_pin_hash text,
  requested_at timestamptz not null default now(),
  accepted_at timestamptz,
  started_at timestamptz,
  completed_at timestamptz,
  cancelled_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_ride_offers (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references public.nova_taxi_v1_rides(id) on delete cascade,
  driver_id uuid not null references public.nova_taxi_v1_driver_profiles(user_id) on delete cascade,
  status text not null default 'offered' check (status in ('offered','accepted','rejected','expired','withdrawn')),
  offered_at timestamptz not null default now(),
  responded_at timestamptz,
  expires_at timestamptz not null default (now() + interval '45 seconds'),
  unique (ride_id, driver_id)
);

create table if not exists public.nova_taxi_v1_ride_events (
  id bigint generated always as identity primary key,
  ride_id uuid not null references public.nova_taxi_v1_rides(id) on delete cascade,
  actor_id uuid references auth.users(id),
  event_type text not null,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_driver_locations (
  driver_id uuid primary key references public.nova_taxi_v1_driver_profiles(user_id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  bearing double precision check (bearing is null or bearing between 0 and 360),
  accuracy_m double precision check (accuracy_m is null or accuracy_m >= 0),
  recorded_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_payments (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null unique references public.nova_taxi_v1_rides(id),
  method text not null check (method in ('cash','multicaixa_express','reference')),
  amount numeric(12,2) not null check (amount >= 0),
  status text not null default 'pending' check (status in ('pending','initiated','confirmed','failed','refunded')),
  provider_reference text,
  confirmed_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_chat_messages (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references public.nova_taxi_v1_rides(id) on delete cascade,
  sender_id uuid not null references auth.users(id),
  message text not null check (length(trim(message)) between 1 and 2000),
  created_at timestamptz not null default now()
);

create table if not exists public.nova_taxi_v1_ratings (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid not null references public.nova_taxi_v1_rides(id) on delete cascade,
  reviewer_id uuid not null references auth.users(id),
  reviewee_id uuid not null references auth.users(id),
  score integer not null check (score between 1 and 5),
  comment text check (comment is null or length(comment) <= 1000),
  created_at timestamptz not null default now(),
  unique (ride_id, reviewer_id)
);

create table if not exists public.nova_taxi_v1_sos_events (
  id uuid primary key default gen_random_uuid(),
  ride_id uuid references public.nova_taxi_v1_rides(id),
  reporter_id uuid not null references auth.users(id),
  latitude double precision check (latitude is null or latitude between -90 and 90),
  longitude double precision check (longitude is null or longitude between -180 and 180),
  description text,
  status text not null default 'open' check (status in ('open','investigating','resolved','false_alarm')),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

create table if not exists public.nova_taxi_v1_admin_audit (
  id bigint generated always as identity primary key,
  admin_id uuid references auth.users(id),
  action text not null,
  entity_type text not null,
  entity_id text,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists nova_taxi_v1_rides_passenger_status_idx on public.nova_taxi_v1_rides(passenger_id,status,created_at desc);
create index if not exists nova_taxi_v1_rides_driver_status_idx on public.nova_taxi_v1_rides(driver_id,status,created_at desc);
create index if not exists nova_taxi_v1_offers_driver_status_idx on public.nova_taxi_v1_ride_offers(driver_id,status,expires_at);
create index if not exists nova_taxi_v1_events_ride_created_idx on public.nova_taxi_v1_ride_events(ride_id,created_at);
create index if not exists nova_taxi_v1_chat_ride_created_idx on public.nova_taxi_v1_chat_messages(ride_id,created_at);
create index if not exists nova_taxi_v1_sos_status_created_idx on public.nova_taxi_v1_sos_events(status,created_at);

-- RLS: direct client writes are restricted; privileged fields are changed only through server functions.
alter table public.nova_taxi_v1_profiles enable row level security;
alter table public.nova_taxi_v1_driver_profiles enable row level security;
alter table public.nova_taxi_v1_vehicles enable row level security;
alter table public.nova_taxi_v1_fare_rules enable row level security;
alter table public.nova_taxi_v1_commission_rules enable row level security;
alter table public.nova_taxi_v1_rides enable row level security;
alter table public.nova_taxi_v1_ride_offers enable row level security;
alter table public.nova_taxi_v1_ride_events enable row level security;
alter table public.nova_taxi_v1_driver_locations enable row level security;
alter table public.nova_taxi_v1_payments enable row level security;
alter table public.nova_taxi_v1_chat_messages enable row level security;
alter table public.nova_taxi_v1_ratings enable row level security;
alter table public.nova_taxi_v1_sos_events enable row level security;
alter table public.nova_taxi_v1_admin_audit enable row level security;

create policy ntx_profile_select_own on public.nova_taxi_v1_profiles for select to authenticated using (id = (select auth.uid()));
create policy ntx_driver_profile_select_own on public.nova_taxi_v1_driver_profiles for select to authenticated using (user_id = (select auth.uid()));
create policy ntx_vehicle_select_own on public.nova_taxi_v1_vehicles for select to authenticated using (driver_id = (select auth.uid()));
create policy ntx_fares_select_active on public.nova_taxi_v1_fare_rules for select to authenticated using (active = true);
create policy ntx_commission_no_client_select on public.nova_taxi_v1_commission_rules for select to authenticated using (false);
create policy ntx_rides_select_participant on public.nova_taxi_v1_rides for select to authenticated using (passenger_id = (select auth.uid()) or driver_id = (select auth.uid()));
create policy ntx_offers_select_driver on public.nova_taxi_v1_ride_offers for select to authenticated using (driver_id = (select auth.uid()));
create policy ntx_events_select_participant on public.nova_taxi_v1_ride_events for select to authenticated using (exists (select 1 from public.nova_taxi_v1_rides r where r.id = ride_id and (r.passenger_id = (select auth.uid()) or r.driver_id = (select auth.uid()))));
create policy ntx_locations_select_authenticated on public.nova_taxi_v1_driver_locations for select to authenticated using (exists (select 1 from public.nova_taxi_v1_rides r where r.driver_id = driver_id and r.status in ('accepted','driver_arriving','driver_arrived','in_trip') and (r.passenger_id = (select auth.uid()) or r.driver_id = (select auth.uid()))));
create policy ntx_payments_select_participant on public.nova_taxi_v1_payments for select to authenticated using (exists (select 1 from public.nova_taxi_v1_rides r where r.id = ride_id and (r.passenger_id = (select auth.uid()) or r.driver_id = (select auth.uid()))));
create policy ntx_chat_select_participant on public.nova_taxi_v1_chat_messages for select to authenticated using (exists (select 1 from public.nova_taxi_v1_rides r where r.id = ride_id and (r.passenger_id = (select auth.uid()) or r.driver_id = (select auth.uid()))));
create policy ntx_chat_insert_sender on public.nova_taxi_v1_chat_messages for insert to authenticated with check (sender_id = (select auth.uid()) and exists (select 1 from public.nova_taxi_v1_rides r where r.id = ride_id and (r.passenger_id = (select auth.uid()) or r.driver_id = (select auth.uid())) and r.status in ('accepted','driver_arriving','driver_arrived','in_trip')));
create policy ntx_ratings_select_participant on public.nova_taxi_v1_ratings for select to authenticated using (reviewer_id = (select auth.uid()) or reviewee_id = (select auth.uid()));
create policy ntx_ratings_insert_reviewer on public.nova_taxi_v1_ratings for insert to authenticated with check (reviewer_id = (select auth.uid()) and exists (select 1 from public.nova_taxi_v1_rides r where r.id = ride_id and r.status = 'completed' and ((r.passenger_id = (select auth.uid()) and r.driver_id = reviewee_id) or (r.driver_id = (select auth.uid()) and r.passenger_id = reviewee_id))));
create policy ntx_sos_select_reporter on public.nova_taxi_v1_sos_events for select to authenticated using (reporter_id = (select auth.uid()));
create policy ntx_sos_insert_reporter on public.nova_taxi_v1_sos_events for insert to authenticated with check (reporter_id = (select auth.uid()));
-- Admin audit intentionally has no client policies.

-- Revoke direct writes on sensitive tables. Client operations will be added as narrowly scoped RPCs in subsequent migrations.
revoke insert, update, delete on public.nova_taxi_v1_profiles from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_driver_profiles from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_vehicles from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_fare_rules from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_commission_rules from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_rides from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_ride_offers from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_ride_events from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_driver_locations from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_payments from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_ratings from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_sos_events from anon, authenticated;
revoke insert, update, delete on public.nova_taxi_v1_admin_audit from anon, authenticated;

grant select on public.nova_taxi_v1_profiles, public.nova_taxi_v1_driver_profiles, public.nova_taxi_v1_vehicles, public.nova_taxi_v1_fare_rules, public.nova_taxi_v1_rides, public.nova_taxi_v1_ride_offers, public.nova_taxi_v1_ride_events, public.nova_taxi_v1_driver_locations, public.nova_taxi_v1_payments, public.nova_taxi_v1_chat_messages, public.nova_taxi_v1_ratings, public.nova_taxi_v1_sos_events to authenticated;
grant insert on public.nova_taxi_v1_chat_messages, public.nova_taxi_v1_ratings, public.nova_taxi_v1_sos_events to authenticated;

commit;
