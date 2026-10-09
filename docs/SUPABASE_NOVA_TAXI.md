# Configuração Supabase — NOVA Táxi

## Projeto de destino

- URL: https://vgbnnikfsmprcpvtypuh.supabase.co
- Chave cliente: `sb_publishable_qCs2fRvNoGJhopd2LDom6Q_qL2AJbwp`

A chave `sb_publishable_...` é destinada a clientes públicos. A segurança dos dados depende de políticas RLS corretas e permissões mínimas no banco.

## Regras obrigatórias

- Não colocar `service_role`, secret keys, senhas ou tokens administrativos no APK, no frontend ou neste repositório.
- Não considerar a aplicação integrada apenas por guardar estes valores neste documento. O cliente Android e o painel administrativo devem ler a URL e a chave da configuração própria de cada ambiente.
- Antes de ativar a produção, confirmar que este projeto contém as tabelas, funções e políticas RLS esperadas pela aplicação; não criar dados fictícios para simular integração.
- Para Android, guardar configuração local em `local.properties` (não versionado) ou em variáveis seguras do processo de build; disponibilizar apenas a URL e a publishable key no cliente.
- Validar cadastro/login por email, confirmação de email, criação/recuperação do perfil e autorização de passageiro/motorista com contas de teste reais antes de publicar.

## Valores para configuração local

```properties
SUPABASE_URL=https://vgbnnikfsmprcpvtypuh.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_qCs2fRvNoGJhopd2LDom6Q_qL2AJbwp
```

Estes nomes são referências de configuração. Confirmar que o código de build os lê efetivamente antes de considerar a integração concluída.
