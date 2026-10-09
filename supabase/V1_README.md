# Supabase — NOVA Táxi V1

## Destino
Projeto oficial Tudoaqui: vgbnnikfsmprcpvtypuh.

## Migrações desta branch
1. 202610090001_nova_taxi_v1_foundation.sql — schema V1 isolado, índices e RLS inicial.
2. 202610090002_nova_taxi_v1_onboarding_security.sql — onboarding, estado online condicionado à aprovação e aprovação administrativa auditada.

## Estado
Estas migrações estão versionadas no GitHub na branch rebuild/nova-taxi-v1-foundation. **Ainda não foram executadas no Supabase.** É intencional: primeiro precisamos rever a sintaxe e testar num ambiente controlado, porque o projeto Tudoaqui pode conter outros módulos.

## Antes de aplicar
- Confirmar backup e ambiente correto.
- Executar primeiro num projeto de desenvolvimento isolado.
- Confirmar que a claim administrativa app_metadata.role = nova_taxi_admin é atribuída apenas por um processo administrativo confiável.
- Testar permissões anon/authenticated e chamadas sem sessão.
- Testar que um motorista não consegue aprovar-se a si próprio nem ativar-se sem veículo verificado.
- Testar que um passageiro não consegue mudar o próprio tipo de perfil depois do onboarding.
- Não usar service_role no Android.
- Não inserir dados de demonstração.

## Importante sobre autenticação
O RPC de onboarding é chamado depois de o utilizador concluir o OTP real do Supabase Phone Auth. O utilizador deve escolher passageiro ou motorista; o método de pagamento não faz parte do cadastro. A aprovação do motorista continua pendente até uma ação administrativa autorizada.

## Ainda não implementado
- Cotação de tarifa baseada numa rota real.
- Pedido/aceitação transacional de corrida e matching concorrente.
- Pagamentos reais e confirmação de provedores.
- Edge Functions de integrações externas.
- Aplicação Android e painel administrativo completos.
- Testes de integração executados contra uma base de desenvolvimento.

Não considerar a V1 pronta para produção até que esses itens sejam implementados e testados.
