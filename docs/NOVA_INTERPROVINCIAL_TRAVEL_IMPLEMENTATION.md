# Viagens interprovinciais NOVA Táxi — implementação em preparação

## Escopo confirmado
- Origem inicial de pesquisa: Luanda.
- Destino inicial de pesquisa: Benguela.
- Operação: transportadoras parceiras.
- Modalidades de transporte: autocarro e viatura privada com motorista.
- Pagamento: dinheiro ou pagamento integrado, apenas quando a partida tiver essa modalidade ativa.

## Fonte de verdade
Não são criados transportadores, horários, preços, lugares ou reservas de demonstração. O catálogo do passageiro só mostra partidas publicadas associadas a transportadoras com estado `active`; a pesquisa fica vazia até existirem dados reais aprovados.

## Migração
`supabase/migrations/20261009153000_nova_interprovincial_travel.sql` cria:
- transportadoras;
- rotas e paragens;
- partidas, preço em AOA e opções de pagamento;
- reservas, bilhetes e liquidações;
- índices e RLS;
- RPC transacional para reservar lugares com bloqueio da partida e verificação de disponibilidade.

A migração está apenas no branch Git. Não foi aplicada ao projeto Supabase ligado, não alterou produção e ainda precisa de revisão/aprovação antes de ser executada.

## Segurança
- RLS ativo nas tabelas expostas.
- Catálogo só para partidas publicadas, futuras, de rotas publicadas e transportadoras ativas.
- Passageiro só lê as suas reservas e bilhetes.
- Operações administrativas exigem a claim confiável `app_metadata.role=admin`; não usar `user_metadata` para autorizações.
- A RPC valida sessão, modalidade de pagamento, preço confirmado e lugares disponíveis; a operação é transacional.
- Liquidações não são expostas ao cliente móvel.

## Activação pendente
1. Confirmar que o administrador autorizado recebe a claim `app_metadata.role=admin` no JWT.
2. Rever e aplicar a migração no projecto correto.
3. Registar apenas transportadoras reais após validação contratual.
4. Publicar rotas/partidas com horários, preços e inventário confirmados.
5. Integrar o pagamento com o fornecedor de produção e validar webhooks antes de aceitar pagamentos integrados reais.
6. Testar reserva concorrente, cancelamento, reembolso, bilhete e liquidação em ambiente de teste.

## Limitação importante
A opção de pagamento integrado no esquema é uma intenção/configuração de partida, não prova de que BitPay ou outro fornecedor está homologado para esta modalidade. Não se deve marcar como pago com base apenas no cliente.
