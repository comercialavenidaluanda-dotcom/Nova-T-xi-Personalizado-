-- NOVA Taxi V1 onboarding and driver approval RPCs.
-- Requires the foundation migration. Review and test before applying.
begin;

create or replace function public.nova_taxi_v1_is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((auth.jwt() -> 'app_metadata' ->> 'role') = 'nova_taxi_admin', false);
$$;

revoke all on function public.nova_taxi_v1_is_admin() from public, anon;
grant execute on function public.nova_taxi_v1_is_admin() to authenticated;

create or replace function public.nova_taxi_v1_complete_onboarding(
  p_full_name text,
  p_user_type text
)
returns public.nova_taxi_v1_profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.nova_taxi_v1_profiles;
  v_phone text;
begin
  if v_uid is null then
    raise exception 'AUTH_REQUIRED' using errcode = '28000';
  end if;
  if length(trim(coalesce(p_full_name,''))) < 2 or length(trim(p_full_name)) > 120 then
    raise exception 'INVALID_FULL_NAME' using errcode = '22023';
  end if;
  if p_user_type not in ('passenger','driver') then
    raise exception 'INVALID_USER_TYPE' using errcode = '22023';
  end if;

  select u.phone into v_phone from auth.users u where u.id = v_uid;

  insert into public.nova_taxi_v1_profiles(id, full_name, phone, user_type)
  values (v_uid, trim(p_full_name), v_phone, p_user_type)
  on conflict (id) do update
    set full_name = excluded.full_name,
        phone = excluded.phone,
        updated_at = now()
    where public.nova_taxi_v1_profiles.user_type = excluded.user_type
  returning * into v_profile;

  if v_profile.id is null then
    raise exception 'PROFILE_ROLE_IMMUTABLE' using errcode = '42501';
  end if;

  if p_user_type = 'driver' then
    insert into public.nova_taxi_v1_driver_profiles(user_id, approval_status, is_online)
    values (v_uid, 'pending', false)
    on conflict (user_id) do nothing;
  end if;

  return v_profile;
end;
$$;

revoke all on function public.nova_taxi_v1_complete_onboarding(text,text) from public, anon;
grant execute on function public.nova_taxi_v1_complete_onboarding(text,text) to authenticated;

create or replace function public.nova_taxi_v1_set_driver_online(p_is_online boolean)
returns public.nova_taxi_v1_driver_profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_driver public.nova_taxi_v1_driver_profiles;
begin
  if v_uid is null then
    raise exception 'AUTH_REQUIRED' using errcode = '28000';
  end if;

  update public.nova_taxi_v1_driver_profiles d
     set is_online = p_is_online,
         updated_at = now()
   where d.user_id = v_uid
     and d.approval_status = 'approved'
     and (not p_is_online or exists (
       select 1 from public.nova_taxi_v1_vehicles v
       where v.driver_id = v_uid and v.active = true and v.verified_at is not null
     ))
  returning * into v_driver;

  if v_driver.user_id is null then
    raise exception 'DRIVER_NOT_APPROVED_OR_VEHICLE_NOT_VERIFIED' using errcode = '42501';
  end if;
  return v_driver;
end;
$$;

revoke all on function public.nova_taxi_v1_set_driver_online(boolean) from public, anon;
grant execute on function public.nova_taxi_v1_set_driver_online(boolean) to authenticated;

create or replace function public.nova_taxi_v1_admin_set_driver_status(
  p_driver_id uuid,
  p_status text,
  p_notes text default null
)
returns public.nova_taxi_v1_driver_profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_driver public.nova_taxi_v1_driver_profiles;
begin
  if auth.uid() is null or not public.nova_taxi_v1_is_admin() then
    raise exception 'ADMIN_REQUIRED' using errcode = '42501';
  end if;
  if p_status not in ('approved','rejected','suspended','pending') then
    raise exception 'INVALID_DRIVER_STATUS' using errcode = '22023';
  end if;

  update public.nova_taxi_v1_driver_profiles
     set approval_status = p_status,
         verification_notes = left(p_notes, 2000),
         approved_at = case when p_status = 'approved' then now() else null end,
         is_online = case when p_status = 'approved' then is_online else false end,
         updated_at = now()
   where user_id = p_driver_id
  returning * into v_driver;

  if v_driver.user_id is null then
    raise exception 'DRIVER_NOT_FOUND' using errcode = 'P0002';
  end if;

  insert into public.nova_taxi_v1_admin_audit(admin_id, action, entity_type, entity_id, details)
  values (auth.uid(), 'driver_status_changed', 'driver', p_driver_id::text,
          jsonb_build_object('status', p_status, 'notes_present', p_notes is not null));
  return v_driver;
end;
$$;

revoke all on function public.nova_taxi_v1_admin_set_driver_status(uuid,text,text) from public, anon;
grant execute on function public.nova_taxi_v1_admin_set_driver_status(uuid,text,text) to authenticated;

commit;
