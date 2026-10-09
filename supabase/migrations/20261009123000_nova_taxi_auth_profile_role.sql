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
