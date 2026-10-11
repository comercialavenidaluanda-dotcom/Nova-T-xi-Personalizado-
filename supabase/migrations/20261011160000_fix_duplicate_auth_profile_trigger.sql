-- Remove the duplicate auth.users profile trigger and reject unknown signup roles.
-- Keep the existing nova_taxi_auth_user_profile trigger as the single profile creator.

CREATE OR REPLACE FUNCTION public.nova_taxi_create_profile_on_auth_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_role text;
  v_name text;
  v_phone text;
BEGIN
  v_role := lower(btrim(coalesce(
    new.raw_user_meta_data ->> 'tipo_utilizador',
    new.raw_user_meta_data ->> 'role',
    new.raw_user_meta_data ->> 'user_type',
    ''
  )));

  IF v_role NOT IN ('passageiro', 'passenger', 'motorista', 'driver') THEN
    RETURN new;
  END IF;

  IF v_role IN ('motorista', 'driver') THEN
    v_role := 'motorista';
  ELSE
    v_role := 'passageiro';
  END IF;

  v_name := nullif(btrim(coalesce(
    new.raw_user_meta_data ->> 'nome',
    new.raw_user_meta_data ->> 'full_name',
    new.raw_user_meta_data ->> 'name',
    ''
  )), '');

  v_phone := coalesce(
    nullif(btrim(coalesce(
      new.raw_user_meta_data ->> 'telefone',
      new.raw_user_meta_data ->> 'phone',
      ''
    )), ''),
    new.phone
  );

  INSERT INTO public.nova_taxi_profiles (id, tipo_utilizador, nome, telefone, ativo)
  VALUES (new.id, v_role, v_name, v_phone, true)
  ON CONFLICT (id) DO UPDATE
    SET tipo_utilizador = EXCLUDED.tipo_utilizador,
        nome = COALESCE(EXCLUDED.nome, public.nova_taxi_profiles.nome),
        telefone = COALESCE(EXCLUDED.telefone, public.nova_taxi_profiles.telefone),
        atualizado_em = now();

  IF v_role = 'motorista' THEN
    INSERT INTO public.nova_taxi_driver_profiles (id, disponivel, aprovado)
    VALUES (new.id, false, false)
    ON CONFLICT (id) DO NOTHING;
  END IF;

  RETURN new;
END;
$function$;

DROP TRIGGER IF EXISTS nova_taxi_on_auth_user_created ON auth.users;
