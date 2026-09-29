# ADR-005 — Idempotência do webhook por delivery id em tabela no Postgres

- **Status:** Aceita
- **Data:** 2026-09-29

## Contexto

O GitHub entrega webhooks com semântica *at least once*: ele reentrega quando não recebe um 2xx
dentro do prazo, e o painel do App permite reentrega manual a qualquer momento. Uma reentrega do
mesmo evento é normal, não excepcional.

Hoje o `webhook-gateway` publica um `pr.review.requested` para toda entrega válida que passa pelo
filtro de ação. Isso significa que uma reentrega produz uma review inteira duplicada — e o custo
dela não é simbólico. Pela [ADR-001](001-arquitetura-orientada-a-eventos-com-rabbitmq.md), esse
evento faz fan-out para o `diff-fetcher` e para o `orchestrator`, e o `diff-fetcher` dispara os três
agentes de revisão. Cada duplicata paga o diff buscado na API do GitHub e **três chamadas de LLM**,
para produzir uma review que será descartada ou, pior, publicada duas vezes no PR.

A ADR-001 já previu este trabalho ("é preciso garantir idempotência nos consumidores... a fase 2
trata o `deliveryId` do GitHub"), mas sem definir como. Esta ADR define.

Dois fatos do sistema restringem a solução:

- O header `X-GitHub-Delivery` é um identificador único por entrega, estável entre reentregas da
  mesma entrega. É a chave natural.
- `ReviewRequestedEvent` já carrega `deliveryId` desde a fase 1, e a tabela `review` do
  `orchestrator` já tem `delivery_id VARCHAR(64) NOT NULL UNIQUE`. O contrato não precisa mudar.

O `webhook-gateway` é hoje o serviço mais simples do sistema: sem banco, sem estado, só HMAC e
publicação.

## Decisão

O `webhook-gateway` passa a **reservar o `deliveryId` antes de publicar**, numa tabela do Postgres.

**Armazenamento.** Tabela `webhook_delivery` no schema `gateway` do banco `prsentinel` já existente,
com `delivery_id VARCHAR(64)` como **chave primária**. O schema é dedicado (`spring.flyway.schemas:
gateway`) para que o histórico do Flyway do gateway não colida com o do `orchestrator`, que vive no
`public`.

**A trava é a unicidade do banco, não uma consulta.** A reserva é
`INSERT ... ON CONFLICT (delivery_id) DO NOTHING`, e o resultado é "reservei" apenas quando a
operação afeta uma linha. O padrão `SELECT` seguido de `INSERT` condicional fica explicitamente
proibido: ele é um TOCTOU que publica duas vezes sob concorrência e ainda assim passa em teste
sequencial.

**Reserva e publicação na mesma transação.** Se a publicação falhar depois da reserva, a transação
sofre rollback, a linha desaparece e o gateway devolve 5xx — que é exatamente o sinal para o GitHub
reentregar. Sem isso existiria uma falha silenciosa: entrega marcada como processada, evento nunca
publicado, reentrega descartada como duplicada e review que simplesmente nunca acontece.

O `@Transactional` fica no `RequestReviewUseCase`, na camada `application`. É uma exceção
consciente ao `CLAUDE.md`, que diz que `infrastructure` é a única camada que conhece framework — ver
Consequências.

**Resposta ao duplicado: `202 Accepted`,** igual ao caso novo e ao de ação não elegível. A diferença
fica no log, não no status.

**Retenção de 30 dias,** com `@Scheduled` diário e valores configuráveis em
`gateway.idempotency.retention` e `purge-interval`.

## Alternativas consideradas

**Redis com TTL.** Resolveria com expiração nativa e menor latência. Descartado por
desproporcionalidade: introduziria um terceiro tipo de infraestrutura no sistema — com healthcheck,
backup e modo de falha próprios — para guardar uma tabela de quatro colunas, num volume de dezenas
de entregas por dia. O Postgres já está no `docker-compose` e já é dependência do `orchestrator`.

**Cache em memória (Caffeine com TTL).** Custo zero de infraestrutura, mas não resolve o problema:
um restart perde o estado e duas réplicas do gateway não compartilham nada. Só funcionaria enquanto
o serviço fosse único e nunca reiniciasse.

**Não deduplicar no gateway, deixar para o `orchestrator`.** Ele já tem `delivery_id UNIQUE`, então
seria zero código novo. Descartado pelo argumento central desta ADR: quando o evento chega ao
`orchestrator`, o diff já foi buscado e os três agentes já chamaram o LLM. Deduplicar no fim da
cadeia é deduplicar **depois de pagar**.

**Responder `409 Conflict` na duplicata.** Semanticamente mais preciso e operacionalmente pior: o
GitHub reentregaria ao ver um não-2xx, receberia 409 de novo, e a entrega ficaria marcada como falha
permanente no painel do App. Um 409 transforma "já processei isso" em "erro". Descartado.

**`reviewId` determinístico derivado do `deliveryId`.** Faria uma reentrega após o expurgo cair no
mesmo `reviewId`. Descartado porque conflita com a semântica desejada de reentrega manual ("processe
de novo"), e o `orchestrator` já cobre o caso pela unicidade de `delivery_id`.

**Sem expurgo.** A tabela cresceria uns 40 mil registros por ano — irrelevante em espaço. Descartado
por um motivo funcional, não de armazenamento: com retenção, uma reentrega manual de um webhook
antigo volta a ser processada, em vez de ser silenciosamente engolida como duplicada.

## Consequências

**Positivas**

- Uma reentrega não gera review duplicada, e a economia é de três chamadas de LLM por ocorrência.
- A garantia vale com múltiplas réplicas do gateway e sobrevive a restart, porque a trava está no
  banco e não no processo.
- O caminho de erro é seguro por construção: ou reserva e publica, ou nada acontece e o GitHub
  reentrega.
- Nenhuma mudança de contrato de evento — `deliveryId` já existia.
- Reentrega manual dentro de 30 dias continua funcionando como pedido explícito de reprocessamento.

**Negativas**

- **O serviço mais simples do sistema ganha uma dependência de banco.** Com o Postgres fora, o
  gateway responde 5xx e passa a depender da janela de reentrega automática do GitHub, que é curta.
  Uma indisponibilidade longa perde reviews. É o custo assumido desta decisão.
- O gateway deixa de ser stateless, o que encarece escalar horizontalmente e subir o ambiente local.
- `@Transactional` na `application` contraria o texto do `CLAUDE.md`. As regras do `ArchitectureTest`
  continuam sendo respeitadas (elas proíbem `domain` → Spring e `application` → `infrastructure`,
  não anotação transacional na `application`), mas a exceção é real e fica registrada aqui em vez de
  passar despercebida numa review.
- Gateway e `orchestrator` compartilham o banco com históricos de Flyway separados por schema. É
  mais uma coisa a não errar ao adicionar migration, e exige teste de integração provando que os
  dois esquemas convivem.
- Resta uma janela irredutível: publicação bem-sucedida seguida de falha no commit gera reentrega e
  segunda publicação. É *at least once* de novo, coberto pela ADR-001 e pela unicidade de
  `delivery_id` no `orchestrator`.
