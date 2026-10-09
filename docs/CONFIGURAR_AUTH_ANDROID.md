# Configuração de autenticação Android — NOVA Táxi

## Projeto correto
- Supabase URL: `https://vgbnnikfsmprcpvtypuh.supabase.co`
- Publishable key: configurada no `android/app/build.gradle.kts` como chave pública do cliente. Nunca usar `service_role` nem uma secret key no Android.
- O projeto antigo `earucsaqqtbllnqsxvlb` não deve ser usado por esta aplicação.

## Deep link de confirmação
No Supabase Dashboard do projeto acima, abrir **Authentication → URL Configuration → Redirect URLs** e adicionar exatamente:

```text
ao.novataxi.app://auth/callback
```

O APK já regista este esquema no AndroidManifest e configura o cliente Auth para o host `auth` e esquema `ao.novataxi.app`. A confirmação por e-mail usa este redirect. Se a URL não estiver na allow-list do Supabase, o fornecedor pode redirecionar para a Site URL em vez de abrir a aplicação.

## Migration necessária para perfis de motorista
A migration `supabase/migrations/20261009123000_nova_taxi_auth_profile_role.sql` atualiza o trigger de criação do perfil para respeitar apenas os dois tipos permitidos (`passageiro` e `motorista`) enviados no cadastro. Motoristas continuam sempre não aprovados por defeito; a aprovação nunca é controlada pelo cliente Android.

Antes de testar cadastro de motorista, rever e aplicar esta migration no projeto Supabase `vgbnnikfsmprcpvtypuh`. Esta branch **não aplica automaticamente** alterações à base de dados de produção.

## Build Android
A chave pública pode ser substituída sem editar o código:

```bash
gradle -p android assembleDebug --no-daemon
```

Ou fornecer uma propriedade/variável `SUPABASE_PUBLISHABLE_KEY` ao Gradle. A chave publishable é pública por desenho; nunca colocar credenciais administrativas no build.

## Testes manuais mínimos
1. Criar conta de passageiro com e-mail novo; verificar a mensagem de confirmação.
2. Abrir o link recebido no e-mail; confirmar que o Android abre o NOVA Táxi.
3. Confirmar que a sessão é restaurada e o perfil do passageiro aparece.
4. Repetir com motorista; confirmar que o perfil de motorista fica com `aprovado = false`.
5. Tentar ativar GPS antes da aprovação; o aplicativo deve bloquear o envio.
6. Entrar com conta existente, terminar sessão e entrar novamente.
