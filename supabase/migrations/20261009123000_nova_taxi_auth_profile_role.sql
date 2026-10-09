-- Keep Auth signup aligned with the existing NOVA Táxi profile schema.
-- A user may request passenger or driver status at signup; driver approval is always
-- false by default and must remain an administrator-controlled server-side decision.
create or replace function public.nova_taxi_create_profile_on_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
  requested_tipo text;
  requested_nome text;
begin
  requested_tipo := case
    when new.raw_user_meta_data ->> 'tipo_utilizador' = 'motorista' then 'motorista'
    else 'passageiro'
  end;
  requested_nome := nullif(btrim(new.raw_user_meta_data ->> 'nome'), '');

  insert into public.nova_taxi_profiles (id, tipo_utilizador, nome, telefone)
  values (new.id, requested_tipo, requested_nome, new.phone)
  on conflict (id) do update
    set nome = coalesce(excluded.nome, public.nova_taxi_profiles.nome),
        telefone = coalesce(excluded.telefone, public.nova_taxi_profiles.telefone);

  if requested_tipo = 'motorista' then
    insert into public.nova_taxi_driver_profiles (id)
    values (new.id)
    on conflict (id) do nothing;
  end if;

  return new;
end;
$function$;

-- Do not let a client promote itself, activate a disabled account, or approve itself.
-- Profile owners may only edit contact fields; administrator approval uses the
-- existing private.nova_taxi_admin_set_driver_approval() server-side function.
revoke update on public.nova_taxi_profiles from authenticated;
revoke update (tipo_utilizador, atualizado_em) on public.nova_taxi_profiles from authenticated;
grant update (nome, telefone) on public.nova_taxi_profiles to authenticated;

drop policy if exists driver_profile_update_own on public.nova_taxi_driver_profiles;
drop policy if exists driver_profile_update_availability_own on public.nova_taxi_driver_profiles;
create policy driver_profile_update_availability_own
  on public.nova_taxi_driver_profiles
  for update
  to authenticated
  using (id = (select auth.uid()))
  with check (
    id = (select auth.uid())
    and (aprovado = true or disponivel = false)
  );

revoke update on public.nova_taxi_driver_profiles from authenticated;
revoke update (id, atualizado_em, aprovado) on public.nova_taxi_driver_profiles from authenticated;
grant update (disponivel) on public.nova_taxi_driver_profiles to authenticated;
