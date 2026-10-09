-- NOVA Táxi admin + GPS live monitoring
-- Additive migration: does not create users or demo data.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to authenticated;

create table if not exists private.nova_taxi_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  active boolean not null default true,
  created_at timestamptz not null default now()
);
alter table private.nova_taxi_admins enable row level security;
revoke all on private.nova_taxi_admins from public, anon, authenticated;

create or replace function private.nova_taxi_is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from private.nova_taxi_admins a
    where a.user_id = (select auth.uid()) and a.active = true
  );
$$;
revoke all on function private.nova_taxi_is_admin() from public, anon;
grant execute on function private.nova_taxi_is_admin() to authenticated;

create or replace function public.nova_taxi_admin_is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((select private.nova_taxi_is_admin()), false);
$$;
revoke all on function public.nova_taxi_admin_is_admin() from public, anon;
grant execute on function public.nova_taxi_admin_is_admin() to authenticated;

-- One current GPS point per driver; history is intentionally not retained in this table.
create table if not exists public.nova_taxi_driver_locations (
  motorista_id uuid primary key references public.nova_taxi_driver_profiles(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  heading double precision check (heading is null or heading between 0 and 360),
  accuracy_m double precision check (accuracy_m is null or accuracy_m between 0 and 10000),
  captured_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create or replace function private.nova_taxi_stamp_driver_location()
returns trigger
language plpgsql
set search_path = ''
as $
begin
  new.captured_at = now();
  new.updated_at = now();
  return new;
end;
$;
revoke all on function private.nova_taxi_stamp_driver_location() from public, anon, authenticated;
drop trigger if exists nova_taxi_stamp_driver_location on public.nova_taxi_driver_locations;
create trigger nova_taxi_stamp_driver_location
before insert or update on public.nova_taxi_driver_locations
for each row execute function private.nova_taxi_stamp_driver_location();

create index if not exists nova_taxi_driver_locations_updated_at_idx
  on public.nova_taxi_driver_locations(updated_at desc);
alter table public.nova_taxi_driver_locations enable row level security;
revoke all on public.nova_taxi_driver_locations from anon, public;
grant select, insert, update on public.nova_taxi_driver_locations to authenticated;

drop policy if exists admin_select_nova_taxi_profiles on public.nova_taxi_profiles;
create policy admin_select_nova_taxi_profiles on public.nova_taxi_profiles
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists admin_select_nova_taxi_driver_profiles on public.nova_taxi_driver_profiles;
create policy admin_select_nova_taxi_driver_profiles on public.nova_taxi_driver_profiles
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists admin_select_nova_taxi_rides on public.nova_taxi_rides;
create policy admin_select_nova_taxi_rides on public.nova_taxi_rides
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists admin_select_nova_taxi_vehicles on public.nova_taxi_vehicles;
create policy admin_select_nova_taxi_vehicles on public.nova_taxi_vehicles
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists admin_select_nova_taxi_payments on public.nova_taxi_payments;
create policy admin_select_nova_taxi_payments on public.nova_taxi_payments
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists admin_select_nova_taxi_sos_events on public.nova_taxi_sos_events;
create policy admin_select_nova_taxi_sos_events on public.nova_taxi_sos_events
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists driver_location_select_own on public.nova_taxi_driver_locations;
create policy driver_location_select_own on public.nova_taxi_driver_locations
for select to authenticated using (motorista_id = (select auth.uid()));

drop policy if exists driver_location_insert_approved on public.nova_taxi_driver_locations;
create policy driver_location_insert_approved on public.nova_taxi_driver_locations
for insert to authenticated with check (
  motorista_id = (select auth.uid())
  and exists (
    select 1 from public.nova_taxi_driver_profiles d
    join public.nova_taxi_profiles p on p.id = d.id
    where d.id = (select auth.uid()) and d.aprovado = true
      and d.disponivel = true and p.tipo_utilizador = 'motorista' and p.ativo = true
  )
);

drop policy if exists driver_location_update_approved on public.nova_taxi_driver_locations;
create policy driver_location_update_approved on public.nova_taxi_driver_locations
for update to authenticated
using (motorista_id = (select auth.uid()))
with check (
  motorista_id = (select auth.uid())
  and exists (
    select 1 from public.nova_taxi_driver_profiles d
    join public.nova_taxi_profiles p on p.id = d.id
    where d.id = (select auth.uid()) and d.aprovado = true
      and d.disponivel = true and p.tipo_utilizador = 'motorista' and p.ativo = true
  )
);

drop policy if exists admin_location_select_all on public.nova_taxi_driver_locations;
create policy admin_location_select_all on public.nova_taxi_driver_locations
for select to authenticated using ((select private.nova_taxi_is_admin()));

drop policy if exists passenger_location_during_own_active_ride on public.nova_taxi_driver_locations;
create policy passenger_location_during_own_active_ride on public.nova_taxi_driver_locations
for select to authenticated using (
  exists (
    select 1 from public.nova_taxi_rides r
    where r.motorista_id = nova_taxi_driver_locations.motorista_id
      and r.passageiro_id = (select auth.uid())
      and r.estado in ('aceita', 'motorista_chegando', 'em_curso')
  )
);

create or replace function public.nova_taxi_admin_set_driver_approval(
  p_driver_id uuid,
  p_approved boolean
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null or not (select private.nova_taxi_is_admin()) then
    raise exception 'ADMIN_REQUIRED' using errcode = '42501';
  end if;
  update public.nova_taxi_driver_profiles
     set aprovado = p_approved,
         disponivel = case when p_approved then disponivel else false end,
         atualizado_em = now()
   where id = p_driver_id;
  if not found then raise exception 'DRIVER_NOT_FOUND'; end if;
end;
$$;
revoke all on function public.nova_taxi_admin_set_driver_approval(uuid, boolean) from public, anon;
grant execute on function public.nova_taxi_admin_set_driver_approval(uuid, boolean) to authenticated;

create or replace function public.nova_taxi_admin_set_profile_active(
  p_user_id uuid,
  p_active boolean
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null or not (select private.nova_taxi_is_admin()) then
    raise exception 'ADMIN_REQUIRED' using errcode = '42501';
  end if;
  update public.nova_taxi_profiles
     set ativo = p_active, atualizado_em = now()
   where id = p_user_id;
  if not found then raise exception 'USER_NOT_FOUND'; end if;
  if not p_active then
    update public.nova_taxi_driver_profiles
       set disponivel = false, atualizado_em = now()
     where id = p_user_id;
  end if;
end;
$$;
revoke all on function public.nova_taxi_admin_set_profile_active(uuid, boolean) from public, anon;
grant execute on function public.nova_taxi_admin_set_profile_active(uuid, boolean) to authenticated;

-- Add only monitoring tables that exist in this schema to Supabase Realtime.
do $$
declare t text;
begin
  foreach t in array array[
    'nova_taxi_profiles','nova_taxi_driver_profiles','nova_taxi_rides',
    'nova_taxi_vehicles','nova_taxi_payments','nova_taxi_sos_events',
    'nova_taxi_driver_locations'
  ] loop
    if not exists (
      select 1 from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public' and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;
