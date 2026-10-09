# Parceiros NOVA — gestão inteligente de frotas

## Estado
A base de dados aditiva está aplicada ao Supabase NOVA Táxi. Nenhum veículo, empresa, alerta, subscrição ou utilizador fictício foi criado. O menu Android e o painel administrativo ainda precisam de ser ligados a estes módulos e testados.

## Módulos
- Empresas/frotas: registo com estado inicial `pending`; aprovação operacional continua a ser uma ação administrativa.
- Membros: papéis `manager`, `dispatcher`, `driver` e `finance`.
- Viaturas: matrícula, estado, motorista atribuído e quilometragem.
- Geocercas: centro, raio e regras JSON por frota.
- Alertas: eventos gerados pelo servidor; clientes autenticados não podem inserir alertas diretamente.
- Manutenção: agenda, categoria, custo AOA, prestador e estado.
- Subscrição: campos para plano e comissão, sem ativar cobrança nem publicar preços automaticamente.

## Segurança e deteção
- RLS ativado em todas as tabelas novas.
- A leitura/gestão é limitada ao proprietário e membros autorizados da frota.
- Um trigger de servidor avalia posições GPS confiáveis contra geocercas ativas e cria alertas `geofence_exit` com deduplicação de 10 minutos.
- A geocerca só é avaliada quando a frota e a viatura estão ativas e existe motorista atribuído à viatura.
- A tabela de alertas é adicionada ao Supabase Realtime quando a publicação está disponível; o painel ainda precisa de subscrever e apresentar os eventos.
- Desvio de rota, perda de sinal e alertas push/SMS precisam de lógica separada.
- Não existe imobilização remota da viatura.

## Planos comerciais (proposta, não publicada)
- Start: gratuito para experimentar.
- Pro: hipótese de 15.000 Kz/mês.
- Enterprise: preço negociado.
- Comissões ilustrativas para avaliação: individual 20%, frota verificada 15–18%, volume maior sob contrato. Não configurar estas percentagens como regra de cobrança até validar custos e aprovar condições comerciais.

## Próximas integrações
1. Ecrã Motorista: online/offline, corridas, ganhos, viatura, documentos, manutenção, segurança e apoio.
2. Parceiros NOVA: pedido de adesão, aprovação, membros, viaturas e planos.
3. Painel administrativo: alertas Realtime, reconhecimento/resolução e registo de auditoria.
4. Implementar deteção de desvio de rota e perda prolongada de sinal.
5. Testar com contas e viaturas reais, sem dados fictícios.
