-- Keep privileged admin logic in the non-exposed private schema.
create or replace function private.nova_taxi_admin_set_driver_approval(p_driver_id uuid,p_approved boolean)
returns void language plpgsql security definer set search_path = ''
as $$
begin
 if (select auth.uid()) is null or not (select private.nova_taxi_is_admin()) then raise exception 'ADMIN_REQUIRED' using errcode='42501'; end if;
 update public.nova_taxi_driver_profiles set aprovado=p_approved, disponivel=case when p_approved then disponivel else false end, atualizado_em=now() where id=p_driver_id;
 if not found then raise exception 'DRIVER_NOT_FOUND'; end if;
end;
$$;
revoke all on function private.nova_taxi_admin_set_driver_approval(uuid,boolean) from public, anon;
grant execute on function private.nova_taxi_admin_set_driver_approval(uuid,boolean) to authenticated;
create or replace function public.nova_taxi_admin_set_driver_approval(p_driver_id uuid,p_approved boolean)
returns void language plpgsql security invoker set search_path = ''
as $$ begin perform private.nova_taxi_admin_set_driver_approval(p_driver_id,p_approved); end; $$;
revoke all on function public.nova_taxi_admin_set_driver_approval(uuid,boolean) from public, anon;
grant execute on function public.nova_taxi_admin_set_driver_approval(uuid,boolean) to authenticated;

create or replace function private.nova_taxi_admin_set_profile_active(p_user_id uuid,p_active boolean)
returns void language plpgsql security definer set search_path = ''
as $$
begin
 if (select auth.uid()) is null or not (select private.nova_taxi_is_admin()) then raise exception 'ADMIN_REQUIRED' using errcode='42501'; end if;
 update public.nova_taxi_profiles set ativo=p_active, atualizado_em=now() where id=p_user_id;
 if not found then raise exception 'USER_NOT_FOUND'; end if;
 if not p_active then update public.nova_taxi_driver_profiles set disponivel=false, atualizado_em=now() where id=p_user_id; end if;
end;
$$;
revoke all on function private.nova_taxi_admin_set_profile_active(uuid,boolean) from public, anon;
grant execute on function private.nova_taxi_admin_set_profile_active(uuid,boolean) to authenticated;
create or replace function public.nova_taxi_admin_set_profile_active(p_user_id uuid,p_active boolean)
returns void language plpgsql security invoker set search_path = ''
as $$ begin perform private.nova_taxi_admin_set_profile_active(p_user_id,p_active); end; $$;
revoke all on function public.nova_taxi_admin_set_profile_active(uuid,boolean) from public, anon;
grant execute on function public.nova_taxi_admin_set_profile_active(uuid,boolean) to authenticated;

create or replace function private.nova_taxi_admin_financial_ledger()
returns table (corrida_id uuid,motorista_id uuid,valor_bruto numeric,percentual numeric,valor_comissao numeric,valor_motorista numeric,criado_em timestamptz)
language plpgsql stable security definer set search_path = ''
as $$
begin
 if (select auth.uid()) is null or not (select private.nova_taxi_is_admin()) then raise exception 'ADMIN_REQUIRED' using errcode='42501'; end if;
 return query select l.corrida_id,l.motorista_id,l.valor_bruto,l.percentual,l.valor_comissao,l.valor_motorista,l.criado_em from private.nova_taxi_commission_ledger l order by l.criado_em desc limit 500;
end;
$$;
revoke all on function private.nova_taxi_admin_financial_ledger() from public, anon;
grant execute on function private.nova_taxi_admin_financial_ledger() to authenticated;
create or replace function public.nova_taxi_admin_financial_ledger()
returns table (corrida_id uuid,motorista_id uuid,valor_bruto numeric,percentual numeric,valor_comissao numeric,valor_motorista numeric,criado_em timestamptz)
language sql stable security invoker set search_path = ''
as $$ select * from private.nova_taxi_admin_financial_ledger(); $$;
revoke all on function public.nova_taxi_admin_financial_ledger() from public, anon;
grant execute on function public.nova_taxi_admin_financial_ledger() to authenticated;
create or replace function public.nova_taxi_admin_is_admin()
returns boolean language sql stable security invoker set search_path = ''
as $$ select coalesce((select private.nova_taxi_is_admin()),false); $$;
revoke all on function public.nova_taxi_admin_is_admin() from public, anon;
grant execute on function public.nova_taxi_admin_is_admin() to authenticated;
