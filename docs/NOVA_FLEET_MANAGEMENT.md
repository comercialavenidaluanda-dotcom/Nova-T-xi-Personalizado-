# Parceiros NOVA — gestão inteligente de frotas

## Estado
Base de dados aditiva aplicada ao projeto Supabase NOVA Táxi. Nenhum veículo, empresa, alerta, subscrição ou utilizador fictício foi criado. A aplicação Android e o painel ainda precisam de ser ligados a estes módulos e testados.

## Módulos
- Empresas/frotas: registo com estado inicial `pending`; aprovação operacional continua a ser uma ação administrativa.
- Membros: papéis `manager`, `dispatcher`, `driver` e `finance`.
- Viaturas: matrícula, estado, motorista atribuído e quilometragem.
- Geocercas: centro, raio e regras JSON; são configurações, não deteção automática já ativa.
- Alertas: eventos gerados pelo servidor; clientes autenticados não podem inserir alertas diretamente.
- Manutenção: agenda, categoria, custo AOA, prestador e estado.
- Subscrição: campos para plano e comissão, sem ativar cobrança nem publicar preços automaticamente.

## Segurança
- RLS ativado em todas as tabelas novas.
- A leitura/gestão é limitada ao proprietário e membros autorizados da frota.
- Alertas são apenas de leitura/atualização para gestores; a criação deve vir de processamento confiável do servidor.
- A posição GPS, o desvio de rota e a perda de sinal ainda precisam de um avaliador no servidor e de notificações.
- Não existe imobilização remota da viatura.

## Planos comerciais (proposta, não publicada)
- Start: gratuito para experimentar.
- Pro: hipótese de 15.000 Kz/mês.
- Enterprise: preço negociado.
- Comissões ilustrativas para avaliação: individual 20%, frota verificada 15–18%, volume maior sob contrato. Não configurar estas percentagens como regra de cobrança até validar custos e aprovar condições comerciais.

## Próximas integrações
1. Ecrã Motorista: online/offline, corridas, ganhos, viatura, documentos, manutenção, segurança e apoio.
2. Parceiros NOVA: pedido de adesão, aprovação, membros, viaturas e planos.
3. Processamento de posições: geocerca, persistência de desvios, deduplicação de alertas e notificação do painel.
4. Testes com contas e viaturas reais, sem dados fictícios.
