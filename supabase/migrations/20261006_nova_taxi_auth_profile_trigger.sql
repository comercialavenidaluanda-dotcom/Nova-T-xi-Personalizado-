-- NOVA Táxi: create a real app profile for every new Supabase Auth user.
-- Applied to project earucsaqqtbllnqsxvlb on 2026-10-06.
-- No seed/demo/test data is inserted.

create or replace function public.nova_taxi_create_profile_on_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.nova_taxi_profiles (
    user_id,
    role,
    full_name,
    phone,
    status
  )
  values (
    new.id,
    'PASSENGER',
    coalesce(new.raw_user_meta_data ->> 'full_name', ''),
    new.phone,
    'ACTIVE'
  )
  on conflict (user_id) do nothing;

  return new;
end;
$$;

revoke execute on function public.nova_taxi_create_profile_on_auth_user()
from public, anon, authenticated;

drop trigger if exists nova_taxi_on_auth_user_created on auth.users;

create trigger nova_taxi_on_auth_user_created
after insert on auth.users
for each row
execute function public.nova_taxi_create_profile_on_auth_user();
