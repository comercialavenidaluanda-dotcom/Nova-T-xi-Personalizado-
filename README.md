# NOVA Táxi — V1

Plataforma de táxi e delivery para Angola.

## V1
- Passageiro e motorista
- Cadastro e autenticação
- Motorista online/offline
- Localização/GPS
- Solicitação e matching de corridas
- Estados da corrida: solicitado → aceite → em viagem → concluído → cancelado
- Categorias: Económico, Cool, Executivo, VIP e Bike
- Pagamentos: Cash, Multicaixa Express e Referência
- Comissão da plataforma: 20%
- Histórico e avaliações
- Supabase como backend
- Painel administrativo
- Segurança e auditoria

## Estrutura
- `android/` — aplicação Android
- `supabase/` — banco, migrations, RPCs e Edge Functions
- `admin/` — painel administrativo
- `docs/` — arquitetura, segurança e API
- `.github/workflows/` — CI

## Regra de negócio
A comissão padrão da plataforma na V1 é **20%** por corrida concluída.

> Nunca guardar chaves privadas, secrets ou credenciais de produção no APK ou no repositório.
