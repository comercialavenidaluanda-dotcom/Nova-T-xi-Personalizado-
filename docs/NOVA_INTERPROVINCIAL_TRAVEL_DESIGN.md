# NOVA Mobilidade — desenho técnico de viagens interprovinciais

Estado: proposta técnica em branch de trabalho; não aplicada à base de dados nem publicada no APK.
Objetivo: acrescentar reservas de viagens interprovinciais sem alterar os fluxos existentes de corridas urbanas.

## Princípios de segurança e compatibilidade

- Não modificar as tabelas ou RPCs atuais de corridas, pagamentos e frotas nesta fase.
- Não inserir empresas, horários, preços, lugares ou reservas fictícias.
- Não anunciar disponibilidade de MACON, TCUL ou outra transportadora sem contrato, autorização e feed/API validado. Se não existir API autorizada, começar por integração manual controlada por operador NOVA.
- Usar AOA como moeda, datas com fuso horário explícito Africa/Luanda e identificadores UUID.
- Reservas, bloqueio de lugares, emissão/cancelamento de bilhetes e liquidações são operações do servidor; nunca confiar em totais calculados pelo cliente.
- Não guardar chaves de serviço no APK. Pagamentos só ficam confirmados após verificação do provedor no servidor.
- Criar e testar políticas RLS por papel antes de qualquer migração de produção.

## Experiência do passageiro

1. Escolher origem, destino, data de partida e número de passageiros.
2. Consultar partidas realmente publicadas por transportadoras aprovadas, com tarifa, paragens, duração estimada, bagagem, política de cancelamento e lugares disponíveis.
3. Selecionar lugar apenas se o operador disponibilizar mapa de assentos.
4. Criar reserva com estado temporário e prazo de expiração, ou confirmação imediata quando a regra da transportadora assim o permitir.
5. Pagar por um método efetivamente integrado e receber bilhete com código/QR verificável no servidor.
6. Consultar, cancelar ou pedir apoio conforme a política contratual.

## Módulos do painel ADM

- Transportadoras: verificação de entidade, documentos, contactos e estado pendente/aprovada/suspensa.
- Rotas e paragens: origem/destino, ordem das paragens, duração estimada e estado.
- Partidas: data/hora, veículo, capacidade, tarifa e publicação.
- Reservas e bilhetes: estado, passageiros, validação de embarque, cancelamentos e reembolsos.
- Financeiro: comissão acordada por contrato, montante bruto, taxas do provedor e valor líquido a liquidar.
- Auditoria: quem alterou horários/tarifas, cancelou bilhete, marcou embarque ou executou uma liquidação.
- Identidade visual: aplicar laranja/branco NOVA e manter os menus urbanos existentes sem regressões.

## Modelo de dados proposto (não criado)

### nova_transport_companies
Campos propostos: id UUID, legal_name, display_name, tax_number, contact_email, contact_phone, status (pending, approved, suspended), created_at, updated_at.

### nova_interprovincial_routes
Campos propostos: id UUID, company_id, origin_name, origin_province, destination_name, destination_province, stops JSONB, estimated_duration_minutes, status, created_at, updated_at.

### nova_trip_schedules
Campos propostos: id UUID, route_id, vehicle_id opcional, departure_at TIMESTAMPTZ, arrival_estimate_at TIMESTAMPTZ opcional, capacity, fare_aoa, status (draft, published, boarding, departed, arrived, cancelled), created_at, updated_at.

### nova_trip_seats (apenas se houver assentos numerados)
Campos propostos: id UUID, schedule_id, seat_code, status, booking_id opcional. Restrição única em (schedule_id, seat_code); bloqueio concorrente no servidor.

### nova_trip_bookings
Campos propostos: id UUID, schedule_id, passenger_user_id, booking_reference, passenger_count, total_aoa, status (pending_payment, confirmed, expired, cancelled, refunded, boarded), expires_at, created_at, updated_at.

### nova_trip_tickets
Campos propostos: id UUID, booking_id, ticket_code_hash, status, issued_at, validated_at opcional, validated_by opcional. Não expor dados pessoais desnecessários no QR.

### nova_transport_settlements
Campos propostos: id UUID, company_id, booking_id, gross_aoa, commission_aoa, provider_fee_aoa, net_aoa, status, created_at, settled_at opcional. Apenas o servidor/financeiro autorizado pode inserir ou alterar valores.

A lista acima é conceptual: validar nomes, convenções e relações existentes antes de gerar migrações.

## Regras de autorização propostas

- Passageiro: só consulta partidas publicadas e as próprias reservas/bilhetes.
- Transportadora: só gere rotas, partidas e reservas associadas à própria empresa e dentro do contrato.
- Operações/dispatcher: permissões limitadas por transportadora e função.
- Financeiro: consulta/liquidações conforme atribuição; não pode alterar partidas nem bilhetes.
- Administrador NOVA: aprova empresas e supervisiona operações; ações críticas auditadas.
- Utilizador anónimo: sem acesso a dados pessoais, reservas, bilhetes ou informação interna.
- Proibir alterações diretas do cliente a tarifa, comissão, total, estado financeiro, proprietário da reserva ou estado de aprovação.

## Integridade transacional e pagamentos

- Impedir sobre-reserva por transação/locking no servidor e restrições únicas; nunca depender apenas de uma consulta prévia do cliente.
- Definir idempotência para criar reserva, iniciar pagamento, processar webhook e emitir bilhete.
- Validar no webhook assinatura, ambiente (sandbox/live), referência, moeda, valor esperado e estado permitido antes de confirmar.
- Aplicar expiração automática de reservas não pagas e libertação segura de lugares.
- Reembolsos e liquidações exigem estados explícitos e trilho de auditoria.
- Começar em sandbox e não ativar produção até existir confirmação documental do provedor e testes ponta a ponta.

## Fases de entrega

1. Rever esquema, permissões, políticas RLS e arquitetura Android/ADM existente.
2. Validar este modelo com a implementação real e produzir migração idempotente/reversível.
3. Aplicar em branch/ambiente de desenvolvimento e testar RLS com utilizadores passageiro, operador, financeiro e administrador.
4. Criar endpoints/RPCs transacionais e testes de concorrência, idempotência e estados inválidos.
5. Integrar o ADM e o Android em branch separada, com identidade laranja/branca.
6. Testar reserva, pagamento sandbox, cancelamento, expiração, validação de embarque e reconciliação.
7. Publicar só após revisão e aprovação explícita.

## Critérios de aceitação

- As corridas urbanas e os fluxos existentes continuam a funcionar.
- Não há lugares duplicados em testes concorrentes.
- Um passageiro não consegue ver ou alterar reservas de outra pessoa.
- Uma transportadora não consegue aceder aos dados de outra.
- Preço, comissão e estado de pagamento são definidos/verificados no servidor.
- Nenhum dado de demonstração aparece como dado real.
- Todas as operações críticas deixam registo auditável.
