# ADR-003 — Review parcial em caso de timeout de agente

- **Status:** Aceita
- **Data:** 2026-09-28

## Contexto

O `orchestrator` só consolida a review quando recebe `pr.agent.completed` dos três agentes. Mas um
agente pode não responder: a API do LLM pode ficar indisponível, devolver 429, estourar o contexto
num diff muito grande, ou o container pode estar fora do ar.

Sem uma política explícita, a review fica presa em `IN_PROGRESS` para sempre e o autor do PR não
recebe retorno nenhum — o pior resultado possível, porque ele fica esperando uma review que nunca
chega.

## Decisão

O `orchestrator` aplica um **timeout por review** (`orchestrator.agent-timeout`, padrão 5 minutos,
verificado a cada `orchestrator.timeout-check-interval`). Quando ele estoura:

- a review passa ao status `PARTIAL`;
- é publicado `pr.review.ready` com o que os agentes que responderam produziram;
- `ReviewReadyEvent.partial` vai como `true` e `missingAgents` lista quais agentes não responderam;
- o `github-publisher` deixa isso explícito no comentário da review.

Ou seja: **entregar uma review incompleta e assumida como incompleta é melhor do que não entregar
nada.** O autor do PR recebe os achados que existem e sabe exatamente o que não foi analisado.

Um `pr.agent.completed` que chegue depois do timeout é registrado (a unicidade
`review_id + agent` garante que não duplique), mas não dispara nova publicação.

## Alternativas consideradas

**Esperar indefinidamente pelos três agentes.** Simples de implementar e garante review completa,
mas trava o fluxo quando um agente falha. Um incidente no provedor do LLM deixaria todos os PRs sem
resposta e sem sinal nenhum de que algo deu errado.

**Falhar a review inteira no timeout.** Também dá retorno ao autor, mas joga fora o trabalho já
feito (e já pago em tokens) pelos agentes que responderam. Se o agente de performance caiu, não há
motivo para descartar os achados de segurança.

**Retry infinito do agente que falhou.** Já existe retry limitado no consumidor
(`max-attempts`, com backoff). Retry infinito só empurra o problema: se o LLM está fora, mais
tentativas não resolvem, e a review continua sem prazo para terminar.

**Timeout por agente, publicando incrementalmente.** Publicar uma review por agente conforme cada um
termina. Descartado: geraria três comentários separados no PR, impediria a deduplicação de achados
repetidos entre agentes e poluiria a conversa do PR.

## Consequências

**Positivas**

- Toda review termina em tempo limitado, com resultado ou com explicação.
- O trabalho dos agentes que responderam é aproveitado.
- `partial` e `missingAgents` tornam a degradação visível para quem lê a review — ninguém confunde
  "nenhum problema de segurança" com "o agente de segurança não rodou".
- A métrica de reviews parciais por agente vira um sinal direto de saúde do sistema (fase 7).

**Negativas**

- O `orchestrator` precisa de um scheduler e de estado durável no Postgres; não dá para manter o
  controle de timeout em memória, ou um restart perderia as reviews em andamento.
- Existe janela de corrida entre o timeout disparar e um `pr.agent.completed` atrasado chegar. O
  tratamento é registrar o resultado tardio sem republicar.
- O valor do timeout é um ajuste empírico: curto demais gera parciais desnecessárias, longo demais
  atrasa o retorno. Começa em 5 minutos e deve ser calibrado com a métrica de latência dos agentes.
