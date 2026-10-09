-- Create NOVA Taxi profiles only for signups explicitly made by the NOVA Taxi app.
-- Safe to re-run; existing profiles are preserved.
create or replace function public.nova_taxi_handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role text;
  v_name text;
  v_phone text;
begin
  v_role := new.raw_user_meta_data ->> 'tipo_utilizador';

  -- This Supabase project may serve other apps; do not classify their users as taxi passengers.
  if v_role is null or v_role not in ('passageiro', 'motorista') then
    return new;
  end if;

  v_name := nullif(btrim(new.raw_user_meta_data ->> 'nome'), '');
  v_phone := nullif(btrim(new.raw_user_meta_data ->> 'telefone'), '');

  insert into public.nova_taxi_profiles (id, tipo_utilizador, nome, telefone, ativo)
  values (new.id, v_role, v_name, v_phone, true)
  on conflict (id) do nothing;

  if v_role = 'motorista' then
    insert into public.nova_taxi_driver_profiles (id, disponivel, aprovado)
    values (new.id, false, false)
    on conflict (id) do nothing;
  end if;

  return new;
end;
$$;

drop trigger if exists nova_taxi_on_auth_user_created on auth.users;
create trigger nova_taxi_on_auth_user_created
  after insert on auth.users
  for each row execute function public.nova_taxi_handle_new_auth_user();

revoke all on function public.nova_taxi_handle_new_auth_user() from public, anon, authenticated;
