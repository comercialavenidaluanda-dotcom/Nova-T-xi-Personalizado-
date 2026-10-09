# PROMPT MESTRE — NOVA Táxi: login, Supabase, identidade visual e popup

Atua como engenheiro sénior Android/Kotlin, Supabase/PostgreSQL, segurança mobile e product designer. Trabalha APENAS no projeto NOVA Táxi deste repositório e no projeto Supabase **vgbnnikfsmprcpvtypuh** (`https://vgbnnikfsmprcpvtypuh.supabase.co`). Não uses nem voltes a ligar o APK ao projeto antigo `earucsaqqtbllnqsxvlb`. Não mistures NOVA KZ, KONECTA ou outros projetos.

## Objetivo
Corrigir o login/cadastro real, alinhar o Android com o esquema Supabase existente, melhorar profundamente o logótipo/ícone e tornar o popup promocional visualmente apelativo, com fotografia comercial realista de pessoas. Não criar dados fictícios, utilizadores de demonstração, corridas falsas nem alegar testes/builds que não foram executados.

## Regras de segurança obrigatórias
1. Nunca colocar `service_role`, secret key, senha administrativa ou credenciais privadas no APK, Gradle, GitHub ou logs. A publishable/anon key pode estar no cliente, mas deve ser fornecida por configuração de build e nunca confundida com uma chave secreta.
2. Não desativar RLS, não tornar tabelas públicas para contornar erros e não conceder permissões amplas a `anon`.
3. Antes de qualquer alteração SQL, inspecionar tabelas, colunas, constraints, triggers, funções e políticas RLS do projeto certo. Criar migrations versionadas, não destrutivas e idempotentes quando possível. Não apagar dados nem recriar tabelas existentes.
4. Não ativar autenticação por telefone/SMS. O fluxo pretendido é por e-mail.
5. Não alterar comissões, tarifas, pagamentos, regras de corrida ou valores de campanha sem requisito confirmado. Não prometer rendimentos garantidos.
6. Não fazer commit direto em `main`; usar branch de correção e apresentar PR/diff. Nunca declarar compilação ou testes aprovados sem os executar.

## Correções Android/Supabase a validar e implementar
O ficheiro `android/app/src/main/java/ao/novataxi/app/MainActivity.kt` tem atualmente:
- URL antiga do Supabase;
- placeholder `%%SUPABASE_PUBLISHABLE_KEY%%`;
- cliente criado apenas com `install(Auth)`, embora use `supabase.from(...)`;
- modelos/perguntas à tabela com `user_id`, `role`, `full_name`, `email`, incompatíveis com o esquema real;
- referência a `nova_taxi_driver_live_locations`, que não deve ser assumida como tabela válida sem verificar o esquema.

O esquema observado no projeto correto inclui:
- `public.nova_taxi_profiles`: `id` (UUID ligado a `auth.users.id`), `tipo_utilizador` (valores permitidos `passageiro` ou `motorista`), `nome`, `telefone`, `ativo`, `criado_em`, `atualizado_em`;
- `public.nova_taxi_driver_profiles`: `id` ligado ao perfil, `disponivel`, `aprovado`, timestamps;
- a tabela de localização observada chama-se `public.nova_taxi_driver_locations`, mas confirmar as colunas e policies antes de a usar.

Implementa o seguinte:
1. Configurar o cliente Supabase com URL correta e instalar explicitamente os módulos necessários, incluindo `Auth` e `Postgrest` segundo a versão da biblioteca instalada. Corrigir o erro “PostgREST not installed” em vez de pedir ao utilizador para instalar extensões no painel Supabase.
2. Retirar o placeholder da chave e criar configuração de build clara e segura (variável de ambiente/Gradle property ou `local.properties` ignorado pelo Git). Falhar com mensagem de configuração clara se a chave estiver ausente. Nunca adicionar chave privada.
3. Adaptar DTOs, consultas e gravações ao esquema real. Não tentar inserir colunas inexistentes. Respeitar as relações por `id` e os valores portugueses de `tipo_utilizador`.
4. Investigar o trigger `private.nova_taxi_create_profile_on_auth_user`: a versão observada cria sempre o tipo passageiro. Corrigir o fluxo de motorista de forma segura, validando os metadados de signup no servidor e sem permitir que o cliente se eleve a administrador. Não criar dois perfis para o mesmo utilizador.
5. O signup com confirmação de e-mail não garante sessão imediata. Apresentar estado claro “confirma o e-mail e depois entra”, manter o ecrã funcional e permitir reenvio de confirmação se suportado. Depois do login, validar sessão, obter o perfil e encaminhar o utilizador corretamente. Tratar erros de credenciais, e-mail não confirmado, rede, RLS e perfil em falta com mensagens úteis sem expor tokens.
6. Configurar o deep link de retorno apenas se o fluxo real de confirmação o usar: AndroidManifest com esquema exclusivo, intent filters corretos e allow-list correspondente nas URLs de Auth do Supabase. Validar que o link abre esta aplicação e não confiar em parâmetros não verificados. Não inventar domínio nem esquema já registado; escolher e documentar um esquema consistente, por exemplo `ao.novataxi.app://auth/callback`, após confirmar que está livre no projeto.
7. Corrigir referências de GPS apenas após confirmar tabela/colunas, permissões e políticas; não engolir silenciosamente erros importantes. Não iniciar tracking de motorista não aprovado.

## Segurança Supabase
- Auditar RLS de todas as tabelas tocadas pelo fluxo.
- Cada utilizador só pode ler/alterar o próprio perfil permitido; não pode mudar o próprio estado de aprovação, comissão, dados de outro utilizador nem privilégios.
- Motoristas começam com `aprovado = false` e `disponivel = false`. Aprovação apenas por administrador autorizado no servidor.
- Rever SECURITY DEFINER, `search_path` fixo e grants mínimos para funções chamadas pelo cliente.
- Não ativar políticas até verificar constraints e comportamento do trigger.
- Testar pelo menos: signup passageiro, signup motorista, confirmação de e-mail, login com e sem confirmação, perfil já existente, sessão expirada, utilizador sem perfil e utilizador não autorizado.

## Identidade visual e popup
Redesenhar para parecer um produto de mobilidade profissional, não um formulário de demonstração:
- Marca: **NOVA Táxi — Pedimos. Chegamos.**
- Não usar azul. Usar uma identidade marcante com coral/laranja, ameixa profunda, marfim e pequeno apontamento verde-lima; bom contraste, tipografia moderna, cantos consistentes e hierarquia visual.
- Criar ícone/logótipo vectorial profissional, legível em tamanhos pequenos e coerente entre launcher, ecrã de autenticação e painel.
- Melhorar o popup promocional: composição rica, fotografia comercial realista de passageiros e motoristas negros/angolanos adultos, expressão natural, contexto urbano contemporâneo de Luanda, carro moderno, apresentação acolhedora e segura. Usar imagens de stock com licença verificada (por exemplo, fornecedor que permita uso comercial), guardar URLs/créditos e incluir fallback local/visual para falha de rede. Não apresentar imagens de pessoas como testemunhos reais nem insinuar que as pessoas fotografadas são utilizadores reais da plataforma.
- Tornar o popup responsivo em ecrãs Android pequenos, com CTA claros: “Quero viajar” e “Quero ser motorista”; acessibilidade, texto legível e fecho funcional.
- Preservar os textos de campanha já aprovados, com ressalva explícita de que ganhos são variáveis e não garantidos. O desconto de 5% nas três primeiras corridas deve ser descrito como oferta sujeita às condições reais da campanha; não inventar regras de elegibilidade.
- Não tornar o popup impossível de fechar; lembrar preferência de apresentação apenas de forma razoável e permitir reabertura a partir do ecrã inicial se fizer sentido.

## Execução e entrega
1. Inspecionar o estado atual do repo e do Supabase antes de editar.
2. Implementar as correções em branch, com alterações pequenas e auditáveis.
3. Criar/atualizar testes para configuração do cliente, modelos/serialização, autenticação e estados de confirmação.
4. Executar build/testes Android disponíveis e reportar os comandos e resultados reais. Se o ambiente não tiver SDK/credenciais, declarar bloqueio exato e não afirmar sucesso.
5. Verificar o diff, procurar secrets e confirmar que nenhuma chave secreta foi adicionada.
6. Entregar PR, lista de ficheiros alterados, migrations aplicadas (se alguma), resultados de testes, configuração manual ainda necessária no Supabase Auth e passos para obter APK. Não publicar automaticamente em produção sem aprovação.
