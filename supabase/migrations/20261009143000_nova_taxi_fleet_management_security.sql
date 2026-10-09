-- NOVA Táxi fleet management, geofencing metadata, security alerts and maintenance.
-- Safe additive migration: does not insert demo users, vehicles, alerts or subscriptions.

create table if not exists public.nova_taxi_fleets (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(trim(name)) between 2 and 140),
  legal_name text,
  tax_number text,
  owner_user_id uuid not null references auth.users(id) on delete restrict,
  status text not null default 'pending' check (status in ('pending','active','suspended')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table if not exists public.nova_taxi_fleet_members (
  id uuid primary key default gen_random_uuid(),
  fleet_id uuid not null references public.nova_taxi_fleets(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('manager','dispatcher','driver','finance')),
  status text not null default 'active' check (status in ('invited','active','suspended')),
  created_at timestamptz not null default now(),
  unique (fleet_id,user_id)
);
create table if not exists public.nova_taxi_fleet_vehicles (
  id uuid primary key default gen_random_uuid(),
  fleet_id uuid not null references public.nova_taxi_fleets(id) on delete cascade,
  taxi_vehicle_id uuid references public.nova_taxi_vehicles(id) on delete set null,
  plate text not null,
  make text,
  model text,
  status text not null default 'pending' check (status in ('pending','active','maintenance','suspended','stolen_reported')),
  current_driver_id uuid references public.nova_taxi_profiles(id) on delete set null,
  odometer_km numeric(12,1) check (odometer_km is null or odometer_km >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (fleet_id,plate)
);
create table if not exists public.nova_taxi_geofences (
  id uuid primary key default gen_random_uuid(),
  fleet_id uuid not null references public.nova_taxi_fleets(id) on delete cascade,
  name text not null,
  center_lat double precision not null check (center_lat between -90 and 90),
  center_lng double precision not null check (center_lng between -180 and 180),
  radius_m integer not null check (radius_m between 50 and 200000),
  enabled boolean not null default true,
  rules jsonb not null default '{}'::jsonb,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table if not exists public.nova_taxi_fleet_security_alerts (
  id uuid primary key default gen_random_uuid(),
  fleet_id uuid not null references public.nova_taxi_fleets(id) on delete cascade,
  fleet_vehicle_id uuid references public.nova_taxi_fleet_vehicles(id) on delete set null,
  driver_id uuid references public.nova_taxi_profiles(id) on delete set null,
  alert_type text not null check (alert_type in ('geofence_exit','route_deviation','gps_signal_lost','unauthorized_hours','sos','theft_reported','tamper_suspected')),
  severity text not null default 'medium' check (severity in ('low','medium','high','critical')),
  status text not null default 'open' check (status in ('open','acknowledged','investigating','resolved','false_positive')),
  latitude double precision check (latitude is null or latitude between -90 and 90),
  longitude double precision check (longitude is null or longitude between -180 and 180),
  details jsonb not null default '{}'::jsonb,
  detected_at timestamptz not null default now(),
  acknowledged_by uuid references auth.users(id) on delete set null,
  acknowledged_at timestamptz,
  resolved_by uuid references auth.users(id) on delete set null,
  resolved_at timestamptz
);
create table if not exists public.nova_taxi_fleet_maintenance (
  id uuid primary key default gen_random_uuid(),
  fleet_id uuid not null references public.nova_taxi_fleets(id) on delete cascade,
  fleet_vehicle_id uuid not null references public.nova_taxi_fleet_vehicles(id) on delete cascade,
  category text not null check (category in ('oil','tires','brakes','inspection','insurance','repair','other')),
  description text not null,
  due_at timestamptz,
  due_odometer_km numeric(12,1) check (due_odometer_km is null or due_odometer_km >= 0),
  cost_aoa numeric(14,2) check (cost_aoa is null or cost_aoa >= 0),
  status text not null default 'planned' check (status in ('planned','booked','in_progress','completed','overdue','cancelled')),
  provider_name text,
  completed_at timestamptz,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table if not exists public.nova_taxi_fleet_subscriptions (
  id uuid primary key default gen_random_uuid(),
  fleet_id uuid not null unique references public.nova_taxi_fleets(id) on delete cascade,
  plan_code text not null default 'start' check (plan_code in ('start','pro','enterprise')),
  status text not null default 'pending' check (status in ('pending','trial','active','past_due','cancelled','suspended')),
  monthly_price_aoa numeric(14,2) not null default 0 check (monthly_price_aoa >= 0),
  commission_percent numeric(5,2) check (commission_percent is null or commission_percent between 0 and 100),
  starts_at timestamptz,
  renews_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists nova_taxi_fleet_members_user_idx on public.nova_taxi_fleet_members(user_id,status);
create index if not exists nova_taxi_fleet_vehicles_fleet_idx on public.nova_taxi_fleet_vehicles(fleet_id,status);
create index if not exists nova_taxi_fleet_alerts_open_idx on public.nova_taxi_fleet_security_alerts(fleet_id,status,detected_at desc);
create index if not exists nova_taxi_fleet_maintenance_due_idx on public.nova_taxi_fleet_maintenance(fleet_id,status,due_at);

create or replace function private.nova_taxi_is_fleet_manager(p_fleet_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$
  select exists (select 1 from public.nova_taxi_fleets f where f.id=p_fleet_id and f.owner_user_id=auth.uid())
  or exists (select 1 from public.nova_taxi_fleet_members m where m.fleet_id=p_fleet_id and m.user_id=auth.uid() and m.status='active' and m.role in ('manager','dispatcher'));
$$;
revoke all on function private.nova_taxi_is_fleet_manager(uuid) from public,anon;
grant execute on function private.nova_taxi_is_fleet_manager(uuid) to authenticated;

alter table public.nova_taxi_fleets enable row level security;
alter table public.nova_taxi_fleet_members enable row level security;
alter table public.nova_taxi_fleet_vehicles enable row level security;
alter table public.nova_taxi_geofences enable row level security;
alter table public.nova_taxi_fleet_security_alerts enable row level security;
alter table public.nova_taxi_fleet_maintenance enable row level security;
alter table public.nova_taxi_fleet_subscriptions enable row level security;

grant select,insert,update on public.nova_taxi_fleets to authenticated;
grant select,insert,update,delete on public.nova_taxi_fleet_members to authenticated;
grant select,insert,update,delete on public.nova_taxi_fleet_vehicles to authenticated;
grant select,insert,update,delete on public.nova_taxi_geofences to authenticated;
grant select,update on public.nova_taxi_fleet_security_alerts to authenticated;
grant select,insert,update,delete on public.nova_taxi_fleet_maintenance to authenticated;
grant select on public.nova_taxi_fleet_subscriptions to authenticated;

drop policy if exists nova_taxi_fleets_select on public.nova_taxi_fleets;
create policy nova_taxi_fleets_select on public.nova_taxi_fleets for select to authenticated using (owner_user_id=auth.uid() or private.nova_taxi_is_fleet_manager(id));
drop policy if exists nova_taxi_fleets_insert on public.nova_taxi_fleets;
create policy nova_taxi_fleets_insert on public.nova_taxi_fleets for insert to authenticated with check (owner_user_id=auth.uid() and status='pending');
drop policy if exists nova_taxi_fleets_update on public.nova_taxi_fleets;
create policy nova_taxi_fleets_update on public.nova_taxi_fleets for update to authenticated using (private.nova_taxi_is_fleet_manager(id)) with check (private.nova_taxi_is_fleet_manager(id));

drop policy if exists nova_taxi_fleet_members_select on public.nova_taxi_fleet_members;
create policy nova_taxi_fleet_members_select on public.nova_taxi_fleet_members for select to authenticated using (user_id=auth.uid() or private.nova_taxi_is_fleet_manager(fleet_id));
drop policy if exists nova_taxi_fleet_members_manage on public.nova_taxi_fleet_members;
create policy nova_taxi_fleet_members_manage on public.nova_taxi_fleet_members for all to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id)) with check (private.nova_taxi_is_fleet_manager(fleet_id));

drop policy if exists nova_taxi_fleet_vehicles_select on public.nova_taxi_fleet_vehicles;
create policy nova_taxi_fleet_vehicles_select on public.nova_taxi_fleet_vehicles for select to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id) or current_driver_id=auth.uid());
drop policy if exists nova_taxi_fleet_vehicles_manage on public.nova_taxi_fleet_vehicles;
create policy nova_taxi_fleet_vehicles_manage on public.nova_taxi_fleet_vehicles for all to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id)) with check (private.nova_taxi_is_fleet_manager(fleet_id));

drop policy if exists nova_taxi_geofences_select on public.nova_taxi_geofences;
create policy nova_taxi_geofences_select on public.nova_taxi_geofences for select to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id));
drop policy if exists nova_taxi_geofences_manage on public.nova_taxi_geofences;
create policy nova_taxi_geofences_manage on public.nova_taxi_geofences for all to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id)) with check (private.nova_taxi_is_fleet_manager(fleet_id));

drop policy if exists nova_taxi_fleet_alerts_select on public.nova_taxi_fleet_security_alerts;
create policy nova_taxi_fleet_alerts_select on public.nova_taxi_fleet_security_alerts for select to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id));
drop policy if exists nova_taxi_fleet_alerts_update on public.nova_taxi_fleet_security_alerts;
create policy nova_taxi_fleet_alerts_update on public.nova_taxi_fleet_security_alerts for update to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id)) with check (private.nova_taxi_is_fleet_manager(fleet_id));

drop policy if exists nova_taxi_fleet_maintenance_select on public.nova_taxi_fleet_maintenance;
create policy nova_taxi_fleet_maintenance_select on public.nova_taxi_fleet_maintenance for select to authenticated
using (private.nova_taxi_is_fleet_manager(fleet_id) or exists (select 1 from public.nova_taxi_fleet_vehicles v where v.id=fleet_vehicle_id and v.current_driver_id=auth.uid()));
drop policy if exists nova_taxi_fleet_maintenance_manage on public.nova_taxi_fleet_maintenance;
create policy nova_taxi_fleet_maintenance_manage on public.nova_taxi_fleet_maintenance for all to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id)) with check (private.nova_taxi_is_fleet_manager(fleet_id));

drop policy if exists nova_taxi_fleet_subscriptions_select on public.nova_taxi_fleet_subscriptions;
create policy nova_taxi_fleet_subscriptions_select on public.nova_taxi_fleet_subscriptions for select to authenticated using (private.nova_taxi_is_fleet_manager(fleet_id));

comment on table public.nova_taxi_fleets is 'Fleet organizations registered with NOVA Táxi; new fleets start pending admin approval.';
comment on table public.nova_taxi_fleet_security_alerts is 'Server-generated fleet safety alerts; authenticated clients cannot insert alerts.';
comment on table public.nova_taxi_geofences is 'Fleet geofence definitions; server-side evaluation and notifications are a separate implementation step.';
