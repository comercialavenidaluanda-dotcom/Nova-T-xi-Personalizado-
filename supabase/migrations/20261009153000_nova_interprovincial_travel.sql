-- NOVA Táxi interprovincial booking schema.
-- This migration is staged in Git only; do not apply to production until reviewed and explicitly approved.
begin;

create table if not exists public.nova_transport_companies (
  id uuid primary key default gen_random_uuid(),
  legal_name text not null,
  display_name text not null,
  contact_phone text,
  contact_email text,
  status text not null default 'pending' check (status in ('pending','active','suspended')),
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.nova_interprovincial_routes (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.nova_transport_companies(id) on delete restrict,
  origin_province text not null,
  destination_province text not null,
  transport_mode text not null check (transport_mode in ('bus','private_vehicle')),
  stops jsonb not null default '[]'::jsonb check (jsonb_typeof(stops) = 'array'),
  status text not null default 'draft' check (status in ('draft','published','archived')),
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (origin_province <> destination_province)
);

create table if not exists public.nova_trip_schedules (
  id uuid primary key default gen_random_uuid(),
  route_id uuid not null references public.nova_interprovincial_routes(id) on delete restrict,
  departure_at timestamptz not null,
  arrival_at timestamptz,
  price_aoa numeric(12,2) check (price_aoa is null or price_aoa >= 0),
  seats_total integer not null check (seats_total > 0 and seats_total <= 100),
  status text not null default 'draft' check (status in ('draft','published','cancelled','completed')),
  payment_cash_enabled boolean not null default true,
  payment_integrated_enabled boolean not null default false,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (arrival_at is null or arrival_at > departure_at),
  check (payment_cash_enabled or payment_integrated_enabled)
);

create table if not exists public.nova_trip_bookings (
  id uuid primary key default gen_random_uuid(),
  schedule_id uuid not null references public.nova_trip_schedules(id) on delete restrict,
  passenger_id uuid not null references auth.users(id) on delete restrict,
  passenger_name text,
  passenger_phone text,
  seat_count integer not null check (seat_count between 1 and 10),
  payment_method text not null check (payment_method in ('cash','integrated')),
  payment_status text not null default 'pending' check (payment_status in ('pending','processing','paid','failed','refunded')),
  booking_status text not null default 'pending' check (booking_status in ('pending','confirmed','cancelled','completed')),
  booking_reference text not null unique default upper(substr(replace(gen_random_uuid()::text,'-',''),1,12)),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.nova_trip_tickets (
  id uuid primary key default gen_random_uuid(),
  booking_id uuid not null unique references public.nova_trip_bookings(id) on delete restrict,
  ticket_code uuid not null unique default gen_random_uuid(),
  issued_at timestamptz not null default now(),
  revoked_at timestamptz
);

create table if not exists public.nova_transport_settlements (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.nova_transport_companies(id) on delete restrict,
  booking_id uuid not null references public.nova_trip_bookings(id) on delete restrict,
  gross_amount_aoa numeric(12,2) not null check (gross_amount_aoa >= 0),
  commission_percent numeric(5,2) not null check (commission_percent between 0 and 100),
  commission_amount_aoa numeric(12,2) not null check (commission_amount_aoa >= 0),
  net_amount_aoa numeric(12,2) not null check (net_amount_aoa >= 0),
  settlement_status text not null default 'pending' check (settlement_status in ('pending','approved','paid','reversed')),
  created_at timestamptz not null default now(),
  unique (booking_id)
);

create index if not exists nova_interprovincial_routes_search_idx
  on public.nova_interprovincial_routes (origin_province, destination_province, transport_mode, status);
create index if not exists nova_trip_schedules_departure_idx
  on public.nova_trip_schedules (departure_at, status, route_id);
create index if not exists nova_trip_bookings_passenger_idx
  on public.nova_trip_bookings (passenger_id, created_at desc);
create index if not exists nova_trip_bookings_schedule_idx
  on public.nova_trip_bookings (schedule_id, booking_status);

alter table public.nova_transport_companies enable row level security;
alter table public.nova_interprovincial_routes enable row level security;
alter table public.nova_trip_schedules enable row level security;
alter table public.nova_trip_bookings enable row level security;
alter table public.nova_trip_tickets enable row level security;
alter table public.nova_transport_settlements enable row level security;

-- Public catalogue is restricted to active partner companies and published trips.
drop policy if exists "published transport companies readable" on public.nova_transport_companies;
create policy "published transport companies readable"
on public.nova_transport_companies for select to authenticated
using (status = 'active');

drop policy if exists "published routes readable" on public.nova_interprovincial_routes;
create policy "published routes readable"
on public.nova_interprovincial_routes for select to authenticated
using (
  status = 'published'
  and exists (
    select 1 from public.nova_transport_companies c
    where c.id = company_id and c.status = 'active'
  )
);

drop policy if exists "published schedules readable" on public.nova_trip_schedules;
create policy "published schedules readable"
on public.nova_trip_schedules for select to authenticated
using (
  status = 'published'
  and departure_at > now()
  and exists (
    select 1 from public.nova_interprovincial_routes r
    join public.nova_transport_companies c on c.id = r.company_id
    where r.id = route_id and r.status = 'published' and c.status = 'active'
  )
);

-- Passengers can only read their own bookings. Booking creation is intended to be routed
-- through a transactional server-side booking function before the app enables live booking.
drop policy if exists "passengers read own bookings" on public.nova_trip_bookings;
create policy "passengers read own bookings"
on public.nova_trip_bookings for select to authenticated
using (passenger_id = (select auth.uid()));

drop policy if exists "passengers read own tickets" on public.nova_trip_tickets;
create policy "passengers read own tickets"
on public.nova_trip_tickets for select to authenticated
using (
  exists (
    select 1 from public.nova_trip_bookings b
    where b.id = booking_id and b.passenger_id = (select auth.uid())
  )
);

-- Financial settlements are intentionally not exposed to mobile users.
revoke all on public.nova_transport_settlements from anon, authenticated;
revoke insert, update, delete on public.nova_transport_companies from anon, authenticated;
revoke insert, update, delete on public.nova_interprovincial_routes from anon, authenticated;
revoke insert, update, delete on public.nova_trip_schedules from anon, authenticated;
revoke insert, update, delete on public.nova_trip_bookings from anon, authenticated;
revoke insert, update, delete on public.nova_trip_tickets from anon, authenticated;

grant select on public.nova_transport_companies, public.nova_interprovincial_routes, public.nova_trip_schedules to authenticated;
grant select on public.nova_trip_bookings, public.nova_trip_tickets to authenticated;


-- Administrative writes are permitted only for JWTs carrying trusted app_metadata role=admin.
-- Do not place admin roles in user_metadata: users can edit that claim.
drop policy if exists "admin manages transport companies" on public.nova_transport_companies;
create policy "admin manages transport companies" on public.nova_transport_companies
for all to authenticated
using ((select auth.jwt()->'app_metadata'->>'role') = 'admin')
with check ((select auth.jwt()->'app_metadata'->>'role') = 'admin');

drop policy if exists "admin manages interprovincial routes" on public.nova_interprovincial_routes;
create policy "admin manages interprovincial routes" on public.nova_interprovincial_routes
for all to authenticated
using ((select auth.jwt()->'app_metadata'->>'role') = 'admin')
with check ((select auth.jwt()->'app_metadata'->>'role') = 'admin');

drop policy if exists "admin manages trip schedules" on public.nova_trip_schedules;
create policy "admin manages trip schedules" on public.nova_trip_schedules
for all to authenticated
using ((select auth.jwt()->'app_metadata'->>'role') = 'admin')
with check ((select auth.jwt()->'app_metadata'->>'role') = 'admin');

drop policy if exists "admin manages bookings" on public.nova_trip_bookings;
create policy "admin manages bookings" on public.nova_trip_bookings
for all to authenticated
using ((select auth.jwt()->'app_metadata'->>'role') = 'admin')
with check ((select auth.jwt()->'app_metadata'->>'role') = 'admin');

drop policy if exists "admin manages tickets" on public.nova_trip_tickets;
create policy "admin manages tickets" on public.nova_trip_tickets
for all to authenticated
using ((select auth.jwt()->'app_metadata'->>'role') = 'admin')
with check ((select auth.jwt()->'app_metadata'->>'role') = 'admin');

grant insert, update, delete on public.nova_transport_companies, public.nova_interprovincial_routes, public.nova_trip_schedules, public.nova_trip_bookings, public.nova_trip_tickets to authenticated;
grant select on public.nova_transport_settlements to authenticated;
drop policy if exists "admin reads settlements" on public.nova_transport_settlements;
create policy "admin reads settlements" on public.nova_transport_settlements
for select to authenticated
using ((select auth.jwt()->'app_metadata'->>'role') = 'admin');

-- Transactional booking prevents overbooking by locking the schedule and checking
-- the total seats in non-cancelled reservations before inserting a pending booking.
create or replace function public.book_nova_interprovincial_trip(
  p_schedule_id uuid,
  p_seat_count integer,
  p_payment_method text,
  p_passenger_name text default null,
  p_passenger_phone text default null
) returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $
declare
  v_user_id uuid := auth.uid();
  v_schedule public.nova_trip_schedules%rowtype;
  v_booked integer;
  v_booking_id uuid;
begin
  if v_user_id is null then
    raise exception 'AUTH_REQUIRED' using errcode = '42501';
  end if;
  if p_seat_count is null or p_seat_count < 1 or p_seat_count > 10 then
    raise exception 'INVALID_SEAT_COUNT' using errcode = '22023';
  end if;
  if p_payment_method not in ('cash','integrated') then
    raise exception 'INVALID_PAYMENT_METHOD' using errcode = '22023';
  end if;

  select s.* into v_schedule
  from public.nova_trip_schedules s
  join public.nova_interprovincial_routes r on r.id = s.route_id
  join public.nova_transport_companies c on c.id = r.company_id
  where s.id = p_schedule_id
    and s.status = 'published'
    and s.departure_at > now()
    and r.status = 'published'
    and c.status = 'active'
  for update of s;

  if not found then
    raise exception 'TRIP_NOT_AVAILABLE' using errcode = 'P0002';
  end if;
  if v_schedule.price_aoa is null then
    raise exception 'PRICE_NOT_CONFIRMED' using errcode = '22023';
  end if;
  if p_payment_method = 'cash' and not v_schedule.payment_cash_enabled then
    raise exception 'CASH_PAYMENT_NOT_AVAILABLE' using errcode = '22023';
  end if;
  if p_payment_method = 'integrated' and not v_schedule.payment_integrated_enabled then
    raise exception 'INTEGRATED_PAYMENT_NOT_AVAILABLE' using errcode = '22023';
  end if;

  select coalesce(sum(b.seat_count),0)::integer into v_booked
  from public.nova_trip_bookings b
  where b.schedule_id = p_schedule_id
    and b.booking_status in ('pending','confirmed');

  if v_booked + p_seat_count > v_schedule.seats_total then
    raise exception 'NOT_ENOUGH_SEATS' using errcode = '23514';
  end if;

  insert into public.nova_trip_bookings(
    schedule_id, passenger_id, passenger_name, passenger_phone, seat_count, payment_method
  ) values (
    p_schedule_id, v_user_id, nullif(trim(p_passenger_name), ''),
    nullif(trim(p_passenger_phone), ''), p_seat_count, p_payment_method
  ) returning id into v_booking_id;
  return v_booking_id;
end;
$;

revoke all on function public.book_nova_interprovincial_trip(uuid, integer, text, text, text) from public, anon;
grant execute on function public.book_nova_interprovincial_trip(uuid, integer, text, text, text) to authenticated;

commit;
