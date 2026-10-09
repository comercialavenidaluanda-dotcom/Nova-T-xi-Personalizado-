-- Prevent suspended or non-passenger profiles from creating ride requests.
drop policy if exists ride_insert_passenger on public.nova_taxi_rides;
create policy ride_insert_passenger on public.nova_taxi_rides
for insert to authenticated
with check (
  passageiro_id = (select auth.uid())
  and motorista_id is null
  and estado = 'solicitada'
  and valor_estimado is null
  and valor_final is null
  and exists (
    select 1 from public.nova_taxi_profiles p
    where p.id = (select auth.uid())
      and p.ativo = true
      and p.tipo_utilizador = 'passageiro'
  )
);
