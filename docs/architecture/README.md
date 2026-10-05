# Arquitetura NOVA Táxi V1

## Componentes
- Android: app passageiro/motorista
- Supabase: Auth, PostgreSQL, RLS, RPCs e Edge Functions
- Admin: operações, motoristas, corridas, pagamentos e métricas

## Fluxo principal
Passageiro solicita → backend valida → matching → motorista aceita → corrida inicia → corrida termina → pagamento → comissão 20% → avaliação.

## Segurança
Toda autorização financeira e de corrida deve ser validada no backend. O cliente Android não é fonte de verdade para saldo, comissão, estado financeiro ou permissões administrativas.
