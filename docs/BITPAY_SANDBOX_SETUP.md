# NOVA Táxi — BitPay Angola (sandbox)

## Estado

- A função `nova-taxi-create-payment-intent` aceita apenas corridas concluídas e pertencentes ao passageiro autenticado.
- A função está limitada ao host de sandbox `https://api-sandbox.bitpay.ao/v1`; não cria cobranças de produção.
- O método Express exige número angolano de 9 dígitos. A referência devolve entidade, número e validade.
- O pagamento só é marcado como pago após webhook `payment.succeeded` com estado `SUCCEEDED` e assinatura HMAC válida.
- Eventos são deduplicados por `BitPay-Event-Id`; o webhook verifica timestamp com tolerância de 10 minutos.
- KWiK e IBAN estão no modelo de produto, mas não são enviados para a API BitPay Angola atual. Exigem prestador/canal bancário autorizado e reconciliação próprios.

## Configurar segredos

No Supabase Dashboard do projeto `vgbnnikfsmprcpvtypuh`, em Edge Functions → Secrets, configurar:
- `BITPAY_SK`: chave secreta sandbox que começa por `sk_test_`.
- `BITPAY_WHSEC`: segredo do webhook que começa por `whsec_`, obtido ao registar o endpoint de webhook na BitPay.
- Confirmar que as variáveis padrão do Supabase estão presentes: `SUPABASE_URL`, `SUPABASE_ANON_KEY` e `SUPABASE_SERVICE_ROLE_KEY`.

Nunca colocar estas chaves no APK, no código público ou em commits.

## Registar webhook

Registar na conta BitPay sandbox o URL:
`https://vgbnnikfsmprcpvtypuh.supabase.co/functions/v1/nova-taxi-bitpay-webhook`

Subscrever eventos de pagamento. Configurar a função `nova-taxi-bitpay-webhook` com verificação JWT desativada no gateway de Edge Functions, porque ela valida a assinatura própria da BitPay (`BitPay-Signature`, HMAC-SHA256) e o identificador do evento. Não desativar a verificação de assinatura no código.

## Testes de sandbox

1. Express aprovado: `923000000`.
2. Rejeitado pelo cliente: `923000001`.
3. Estado UNKNOWN com sucesso tardio: `923000002`.
4. Verificar que a corrida permanece não paga enquanto o estado não chegar por webhook assinado.
5. Repetir o mesmo evento e confirmar que a deduplicação impede o processamento duplicado.

## Produção

A documentação da BitPay Angola informa que a produção depende de certificação EMIS e onboarding aprovado. Não mudar o host para produção nem usar chaves live antes dessa autorização. A integração do gateway não equivale a integração de desembolsos automáticos aos motoristas.
