-- Generate server-side geofence-exit alerts from trusted GPS writes.
-- Requires an active fleet and active fleet vehicle with current_driver_id assigned.
create or replace function private.nova_taxi_evaluate_fleet_geofences()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare v_vehicle record; v_fence record; v_distance_m double precision;
begin
  if new.latitude is null or new.longitude is null then return new; end if;
  for v_vehicle in
    select fv.id as fleet_vehicle_id, fv.fleet_id, fv.current_driver_id
    from public.nova_taxi_fleet_vehicles fv
    join public.nova_taxi_fleets f on f.id=fv.fleet_id
    where fv.current_driver_id=new.motorista_id and fv.status='active' and f.status='active'
  loop
    for v_fence in
      select gf.id, gf.name, gf.center_lat, gf.center_lng, gf.radius_m
      from public.nova_taxi_geofences gf
      where gf.fleet_id=v_vehicle.fleet_id and gf.enabled=true
    loop
      v_distance_m := 6371000 * 2 * asin(sqrt(least(1,
        power(sin(radians(new.latitude-v_fence.center_lat)/2),2)
        + cos(radians(v_fence.center_lat))*cos(radians(new.latitude))
        * power(sin(radians(new.longitude-v_fence.center_lng)/2),2)
      )));
      if v_distance_m > v_fence.radius_m and not exists (
        select 1 from public.nova_taxi_fleet_security_alerts a
        where a.fleet_id=v_vehicle.fleet_id
          and a.fleet_vehicle_id=v_vehicle.fleet_vehicle_id
          and a.alert_type='geofence_exit'
          and a.status in ('open','acknowledged','investigating')
          and a.detected_at > now()-interval '10 minutes'
          and a.details->>'geofence_id'=v_fence.id::text
      ) then
        insert into public.nova_taxi_fleet_security_alerts
          (fleet_id,fleet_vehicle_id,driver_id,alert_type,severity,latitude,longitude,details)
        values (
          v_vehicle.fleet_id,v_vehicle.fleet_vehicle_id,new.motorista_id,'geofence_exit','high',
          new.latitude,new.longitude,
          jsonb_build_object('geofence_id',v_fence.id,'geofence_name',v_fence.name,
            'allowed_radius_m',v_fence.radius_m,'distance_from_center_m',round(v_distance_m::numeric,1),
            'gps_captured_at',new.captured_at)
        );
      end if;
    end loop;
  end loop;
  return new;
end;
$$;
revoke all on function private.nova_taxi_evaluate_fleet_geofences() from public,anon,authenticated;
drop trigger if exists nova_taxi_fleet_geofence_alert on public.nova_taxi_driver_locations;
create trigger nova_taxi_fleet_geofence_alert
after insert or update of latitude,longitude on public.nova_taxi_driver_locations
for each row execute function private.nova_taxi_evaluate_fleet_geofences();
do $$
begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime')
 and not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='nova_taxi_fleet_security_alerts') then
   alter publication supabase_realtime add table public.nova_taxi_fleet_security_alerts;
 end if;
end $$;
comment on function private.nova_taxi_evaluate_fleet_geofences() is
'Creates deduplicated fleet geofence-exit alerts from trusted driver GPS writes. Route deviation and lost-signal alerts require separate evaluators.';
