# ADR-001 — Arquitetura orientada a eventos com RabbitMQ

- **Status:** Aceita
- **Data:** 2026-09-28

## Contexto

Revisar um PR envolve quatro etapas de custo e latência muito diferentes: validar o webhook (ms),
buscar o diff na API do GitHub (centenas de ms, sujeito a rate limit), analisar o diff com um LLM
(dezenas de segundos, podendo falhar ou estourar timeout) e publicar a review (centenas de ms,
também sujeito a rate limit).

O GitHub espera a resposta do webhook em poucos segundos e reentrega o evento se não receber um 2xx
a tempo. Fazer o trabalho de forma síncrona dentro do handler do webhook não é viável.

Além disso, os três agentes de IA analisam o mesmo diff de forma independente, o que pede um fan-out
em que um agente lento ou indisponível não bloqueie os outros.

## Decisão

Separar as etapas em serviços independentes que se comunicam por eventos num **topic exchange do
RabbitMQ** (`pr-sentinel.events`).

- O `webhook-gateway` responde `202 Accepted` assim que valida a assinatura e publica o evento.
- Cada consumidor tem **fila própria** ligada à routing key que lhe interessa, com **DLQ** via
  dead-letter exchange (`pr-sentinel.dlx`).
- O fan-out para os agentes é feito por três filas distintas ligadas à mesma routing key
  `pr.diff.ready`.
- Os contratos ficam centralizados no módulo `common` (records + `Topology`), para que produtor e
  consumidor nunca divirjam.

Routing keys: `pr.review.requested`, `pr.diff.ready`, `pr.agent.completed`, `pr.review.ready`.

## Alternativas consideradas

**Monólito síncrono.** Um único serviço fazendo tudo dentro do request do webhook. Descartado: o
GitHub daria timeout, uma falha no LLM perderia a review inteira e não haveria paralelismo entre os
agentes.

**Chamadas HTTP entre serviços.** Mantém a separação, mas acopla os serviços em tempo de execução: o
`diff-fetcher` precisaria conhecer os três agentes e tratar retry/backoff de cada um. Com fila, o
produtor não sabe quem consome — adicionar um quarto agente é declarar uma fila nova.

**Kafka.** Resolveria o mesmo problema com melhor throughput e retenção. Descartado por peso
operacional desproporcional ao volume esperado (dezenas de PRs por dia); o RabbitMQ entrega roteamento
por topic, DLQ e ack por mensagem com muito menos infraestrutura.

**Spring Events / fila em memória.** Sem durabilidade: reiniciar o serviço perderia reviews em
andamento.

## Consequências

**Positivas**

- Resposta imediata ao GitHub, sem risco de reentrega por timeout.
- Um agente fora do ar não impede os demais de concluir.
- Mensagens rejeitadas após os retries vão para a DLQ e ficam disponíveis para inspeção.
- Adicionar um consumidor não exige mudar o produtor.

**Negativas**

- Depuração fica mais difícil: um fluxo atravessa cinco serviços. Mitigado pelo header de correlação
  `x-correlation-id` propagado com o `reviewId`.
- É preciso garantir idempotência nos consumidores, já que a entrega é *at least once* (fase 2
  trata o `deliveryId` do GitHub; o `orchestrator` usa a unicidade de `review_id + agent`).
- O RabbitMQ vira dependência de infraestrutura em desenvolvimento e em teste (resolvido com
  Testcontainers).
- Não há ordenação global entre eventos de reviews diferentes — aceitável, já que o estado é
  agregado por `reviewId`.
