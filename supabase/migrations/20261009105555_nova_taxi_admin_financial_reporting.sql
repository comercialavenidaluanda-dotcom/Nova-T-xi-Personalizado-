-- Read-only financial reporting for the authenticated NOVA Táxi admin panel.
create or replace function public.nova_taxi_admin_financial_ledger()
returns table (
  corrida_id uuid,
  motorista_id uuid,
  valor_bruto numeric,
  percentual numeric,
  valor_comissao numeric,
  valor_motorista numeric,
  criado_em timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null or not (select private.nova_taxi_is_admin()) then
    raise exception 'ADMIN_REQUIRED' using errcode = '42501';
  end if;
  return query
    select l.corrida_id, l.motorista_id, l.valor_bruto, l.percentual,
           l.valor_comissao, l.valor_motorista, l.criado_em
      from private.nova_taxi_commission_ledger l
     order by l.criado_em desc
     limit 500;
end;
$$;
revoke all on function public.nova_taxi_admin_financial_ledger() from public, anon;
grant execute on function public.nova_taxi_admin_financial_ledger() to authenticated;
