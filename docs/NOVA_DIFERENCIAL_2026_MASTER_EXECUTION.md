# NOVA DIFERENCIAL 2026 — MASTER EXECUTION SPEC

## Objetivo

Evoluir a NOVA Táxi de uma aplicação de transporte para uma plataforma de mobilidade angolana com diferenciação clara em segurança, personalização, aeroporto, entregas e mobilidade empresarial.

## REGRAS ABSOLUTAS

1. Trabalhar exclusivamente no NOVA Táxi.
2. NÃO alterar KONECTA.
3. NÃO alterar NOVA KZ.
4. NÃO criar dados fictícios, passageiros fictícios, motoristas fictícios, pagamentos fictícios ou tarifas hard-coded no APK.
5. Supabase é a fonte de verdade.
6. Toda regra financeira, comissão, tarifa, matching, autorização e transição de corrida deve ser validada no backend.
7. Nunca colocar service_role key, secrets ou credenciais privadas no APK.
8. Comissão padrão: 20% plataforma / 80% motorista, calculada no backend.
9. Não adicionar IBAN como opção de pagamento no frontend da NOVA.
10. Cadastro não deve exigir método de pagamento; pagamento só é escolhido no pedido da corrida.
11. Não quebrar APIs/RPCs existentes; antes de substituir uma RPC, localizar usos no Android e backend e reconciliar contratos.
12. Preservar compatibilidade com o projeto atual e criar migrations incrementais.

## POSICIONAMENTO

NOVA — Mobilidade feita para Angola.

Pilares:
- Segurança
- Experiência personalizada
- Operação adaptada à realidade angolana

## SERVIÇOS

### NOVA Cool
Categoria urbana principal.

### NOVA Executivo
Serviço superior para clientes profissionais e corporativos.

### NOVA VIP
Serviço premium.

### NOVA Bike
Serviço de mobilidade em duas rodas quando disponível.

### NOVA Aeroporto
- aeroporto → residência/hotel/empresa
- residência/hotel → aeroporto
- reserva antecipada
- identificação do motorista
- ponto de encontro
- acompanhamento de voo quando houver integração real disponível
- preço apresentado antes da confirmação
- fluxo VIP/Meet & Assist preparado para evolução futura

### NOVA Entregas
- pessoa → pessoa
- documentos
- pequenos volumes
- encomendas
- objetos esquecidos
- compras
- entregas empresariais
- código de entrega
- prova de entrega
- rastreamento

### NOVA Business
Preparar arquitetura para:
- contas empresariais
- funcionários autorizados
- centros de custo
- limites
- histórico
- recibos
- viagens para clientes
- transporte de colaboradores
- aeroporto ↔ empresa

## NOVA SAFE

Implementar/planejar de forma real e auditável:

- passageiro e motorista identificados
- motorista/veículo visíveis antes da entrada
- PIN de início da corrida
- partilha da viagem
- contacto de emergência
- botão SOS
- detecção de desvio de rota no backend quando tecnicamente suportado
- paragem prolongada/anomalia
- denúncia
- avaliação bilateral
- histórico de segurança
- proteção do motorista
- proteção do passageiro

Não simular GPS, SOS ou eventos de segurança.

## NOVA DRIVER

Preparar modelo de reputação:

- score de segurança
- avaliações
- cancelamentos
- pontualidade
- incidentes confirmados
- tempo na plataforma
- estado de verificação
- benefícios por nível

O score não deve ser usado para bloquear automaticamente sem regra de negócio explícita e auditável.

## NOVA RISK ENGINE

Criar arquitetura preparada para detectar:

- contas duplicadas
- fraude
- abuso promocional
- cancelamentos suspeitos
- padrões anormais
- desvio de rota
- eventos de segurança
- comportamento financeiro anormal

A V1 pode começar com regras determinísticas no backend. IA só deve ser adicionada quando houver dados reais suficientes e sem substituir controles de segurança críticos.

## TARIFAS

Tarifas configuráveis no backend.

Referência inicial já aprovada para a V1:
- mínimo: 500 AOA
- 2,5 km / 6 min incluídos
- 80 AOA/km adicional
- 35 AOA/min adicional
- comissão: 20%

Não hard-code estes valores no APK. Criar configuração por serviço/categoria/zona no Supabase.

O preço final deve ser calculado no servidor e devolvido ao cliente para confirmação.

## CORRIDAS

Máquina de estados controlada no backend:

requested → accepted → driver_arriving → driver_arrived → in_trip → completed

Cancelamentos devem ter regras e motivos auditáveis.

Garantir:
- um motorista não aceita duas corridas incompatíveis
- corrida atribuída não muda de motorista arbitrariamente
- corrida concluída é imutável salvo fluxo administrativo auditado
- operações críticas são idempotentes
- concorrência no matching é segura

## PAGAMENTOS

V1:
- Cash
- Multicaixa Express
- Referência

Sem IBAN no frontend.

Pagamento e comissão devem ser registrados server-side.

## CADASTRO REAL

Passageiro:
- Auth real
- perfil real
- sem dados de demonstração
- sem método de pagamento obrigatório no cadastro

Motorista:
- Auth real
- perfil real
- documentação/estado de verificação
- só fica disponível para receber corridas quando elegível segundo regras reais

O primeiro utilizador de teste deve ser criado no ambiente Supabase real, não inserido como fixture no APK.

## ANDROID

Kotlin + Jetpack Compose + Material 3.

O Android deve:
- consumir apenas APIs/RPCs reais
- não conter dados fictícios
- não confiar no cliente para preço/comissão/permissões
- tratar erros de backend
- impedir exposição de secrets
- manter UX clara e rápida
- preservar a identidade visual NOVA já aprovada

## ADMIN

Painel para:
- motoristas
- passageiros
- corridas
- Aeroporto
- Entregas
- tarifas
- categorias
- incidentes
- verificações
- pagamentos
- comissões
- métricas
- auditoria

Ações administrativas sensíveis devem ser autorizadas no backend e registradas em audit log.

## SUPABASE

Usar migrations incrementais.

Criar/usar namespace exclusivo NOVA Táxi, com prefixos nova_taxi_ quando necessário.

Não alterar tabelas, funções, políticas ou dados pertencentes a KONECTA/NOVA KZ.

RLS obrigatório.

Separar:
- dados públicos mínimos
- dados do próprio utilizador
- dados de motorista
- dados administrativos
- dados financeiros
- audit logs

## SEGURANÇA

Obrigatório:
- CAPTCHA/anti-abuse em Auth quando disponível
- proteção contra leaked passwords
- RLS
- validação server-side
- idempotência
- rate limiting onde aplicável
- logs sem passwords/tokens/PINs
- secrets fora do APK
- ambientes separados
- validação de transições de estado
- proteção de endpoints/RPCs
- auditoria de alterações administrativas

## ORDEM DE EXECUÇÃO

FASE 0 — Auditoria
1. Inspecionar todo o código existente.
2. Mapear Android ↔ RPCs ↔ tabelas ↔ Edge Functions.
3. Encontrar conflitos e duplicações.
4. Não apagar código funcional sem evidência.

FASE 1 — Backend
5. Reconciliar schema/RPCs.
6. Corrigir matching concorrente.
7. Corrigir máquina de estados.
8. Implementar tarifa server-side configurável.
9. Implementar comissão 20%.
10. Corrigir RLS/Auth/anti-abuse.
11. Preparar cadastro real.

FASE 2 — Diferenciais
12. NOVA Safe.
13. NOVA Aeroporto.
14. NOVA Entregas.
15. NOVA Business foundation.
16. Driver Score.
17. Risk Engine determinístico inicial.

FASE 3 — Android
18. Integrar os contratos reais.
19. Implementar fluxos passageiro.
20. Implementar fluxos motorista.
21. Integrar Aeroporto e Entregas.
22. Implementar segurança de corrida.
23. Remover qualquer mock/demo/fake data.

FASE 4 — Admin
24. Operação de corridas.
25. Motoristas.
26. Tarifas.
27. Aeroporto.
28. Entregas.
29. Incidentes.
30. Auditoria.

FASE 5 — Validação
31. Testes unitários.
32. Testes de integração.
33. Testes RLS.
34. Testes de concorrência.
35. Testes de Auth.
36. Testes de tarifa.
37. Testes de comissão.
38. Teste real de cadastro.
39. Teste real de pedido de corrida.
40. Teste real motorista ↔ passageiro.
41. Gerar APK de pré-produção.

## CRITÉRIO DE CONCLUSÃO

Não declarar produção pronta se:
- houver mock/fake data;
- tarifa puder ser manipulada pelo APK;
- service_role estiver no APK;
- RLS estiver incompleto;
- cadastro não criar utilizador real;
- matching tiver condição de corrida duplicada;
- comissão não for server-side;
- RPCs Android/backend estiverem divergentes;
- pagamentos forem apenas simulados;
- segurança crítica estiver apenas no frontend.

## SAÍDA OBRIGATÓRIA DO AGENTE

Depois de executar:
1. listar arquivos alterados;
2. listar migrations criadas;
3. listar RPCs/Edge Functions alteradas;
4. listar fluxos Android alterados;
5. listar testes executados;
6. informar claramente o que PASSOU;
7. informar claramente o que BLOQUEIA produção;
8. nunca inventar resultados de testes ou integrações.
