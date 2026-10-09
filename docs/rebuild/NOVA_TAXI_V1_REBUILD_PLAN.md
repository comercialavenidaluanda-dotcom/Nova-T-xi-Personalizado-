# NOVA Táxi V1 — plano de reconstrução

## Objetivo
Reconstruir o módulo NOVA Táxi de forma incremental, testável e isolada, usando o projeto Supabase oficial Tudoaqui (vgbnnikfsmprcpvtypuh). O projeto Tudoaqui2 é apenas referência de funcionalidades; não será alvo de escrita nem será copiado cegamente.

## Regras de execução
- Não apagar tabelas nem dados existentes.
- Não executar migrations no Supabase automaticamente nesta fase.
- Não tocar em NOVA KZ nem KONECTA.
- Não criar passageiros, motoristas, corridas ou pagamentos fictícios.
- Não colocar service_role, tokens privados ou segredos no APK.
- Backend é fonte de verdade para autorização, estados, tarifas e comissões.
- Criar alterações versionadas numa branch própria e rever antes de integrar em main.

## Diagnóstico inicial
- O módulo atual tem uma implementação Android concentrada no MainActivity.kt, centrada em autenticação/mapa.
- A estrutura atual contém tabelas básicas, mas os fluxos operacionais completos não estão demonstrados.
- As políticas atuais precisam de restringir alterações a campos privilegiados (tipo de perfil, aprovação de motorista, atribuição/estado/preço da corrida).
- O repositório tem documentação de arquitetura, mas os READMEs do Android/Admin/Supabase descrevem áreas ainda por implementar.
- O ícone launcher e a compilação APK precisam de ser verificados na validação do build.

## Arquitetura alvo
1. Android Kotlin + Jetpack Compose: UI, sessão e chamadas autenticadas; sem regras financeiras privilegiadas no cliente.
2. Supabase Auth: telefone + OTP, sessão persistente.
3. PostgreSQL: perfis, motoristas, veículos, tarifas, corridas, eventos, localização, ofertas, pagamentos, comissões, avaliações e auditoria.
4. RPCs transacionais: completar perfil, pedir corrida, aceitar oferta, transições permitidas, concluir corrida e confirmar pagamento.
5. Edge Functions apenas onde necessários para integrações externas/segredos, nunca como substituto de RLS.
6. Painel administrativo web separado com autorização verificada no servidor.

## Fases
### Fase 0 — auditoria e contratos
- [x] Identificar projeto oficial e repositório.
- [x] Registar riscos de RLS no schema atual.
- [ ] Inventariar todas as tabelas, triggers, RPCs, Edge Functions e usos Android.
- [ ] Confirmar configuração real de Phone Auth/SMS e variáveis de build sem revelar segredos.
- [ ] Criar matriz Android → RPC → tabela → política → teste.

### Fase 1 — base de dados V1
- [ ] Preparar schema versionado sem apagar objetos existentes.
- [ ] Perfis e verificação de motorista.
- [ ] Tarifas e comissão configuráveis.
- [ ] Corridas com máquina de estados validada no servidor.
- [ ] Ofertas, localização, eventos, pagamentos e auditoria.
- [ ] RLS por papel e por registo; funções privilegiadas com search_path seguro.
- [ ] Testes de autorização, idempotência e concorrência.

### Fase 2 — Android funcional
- [ ] Ícone e identidade visual.
- [ ] Telefone/OTP, onboarding e sessão persistente.
- [ ] Perfil passageiro e motorista.
- [ ] Ecrãs completos, navegação e estados de erro/loading.
- [ ] Cool e Executivo.
- [ ] Pedido e acompanhamento de corrida.
- [ ] Nenhum mock/dado de demonstração em builds de teste conectados ao backend real.

### Fase 3 — administração e diferenciais
- [ ] Painel de operação e aprovação.
- [ ] SOS, chat e partilha da corrida.
- [ ] Aeroporto e Entregas.
- [ ] NOVA Business e Risk Engine após os fluxos fundamentais estarem estáveis.

### Fase 4 — validação
- [ ] Compilação Gradle e testes automatizados.
- [ ] Teste de cadastro real com OTP.
- [ ] Teste de persistência da sessão.
- [ ] Teste de pedido/aceitação/conclusão de corrida.
- [ ] Testes RLS e tentativas de autoaprovação/alteração de preço.
- [ ] Gerar APK Debug e publicar artefacto apenas após build bem-sucedido.

## Critério de conclusão
Não declarar uma fase pronta sem evidência verificável. Cada entrega deve listar ficheiros alterados, migrations, testes executados, resultado e bloqueios.
