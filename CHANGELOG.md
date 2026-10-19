# Changelog

Todas as mudanças notáveis deste projeto são documentadas neste arquivo.

O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/),
e o versionamento segue [SemVer](https://semver.org/lang/pt-BR/).

## [Unreleased]

### Added

- Agente de segurança ponta a ponta: o `review-agent` consome `pr.diff.ready`, analisa cada chunk
  com o LLM usando o prompt do seu `AGENT_TYPE`, filtra os achados e publica
  `pr.agent.completed`. Falha em qualquer chunk publica `FAILED` em vez de morrer calado — é isso
  que permite ao `orchestrator` fechar uma review parcial
  ([ADR-003](docs/adr/003-review-parcial-em-timeout-de-agente.md)).
- Uma segunda tentativa quando a resposta do modelo não vira o JSON esperado, agora dizendo a ele o
  que errou. Resposta fora de formato é o erro mais comum de LLM e é mais barato pedir de novo que
  reprovar o agente e perder a review.
- Validação de forma dos achados, além do limiar de confiança da
  [ADR-004](docs/adr/004-saida-estruturada-do-llm-com-limiar-de-confianca.md): achado sem arquivo,
  sem severidade, sem título, sem explicação, com linha menor que 1 ou com confiança fora de
  `[0,1]` é descartado. A saída do modelo é entrada não confiável, como o payload do webhook.
- `review-agent` reorganizado nas camadas `domain`, `application` e `infrastructure`, com as portas
  `FindingSource` e `AgentResultPublisher` — é o que permite testar o agente sem chamar o LLM.
- `diff-fetcher` funcional: consome `pr.review.requested`, busca os arquivos do PR na API do GitHub
  paginando pelo header `Link`, descarta o que não vale review (lockfile, gerado, minificado,
  binário e arquivo removido), fatia os patches em fronteira de hunk e publica `pr.diff.ready`
  ([ADR-007](docs/adr/007-diff-por-arquivo-da-api-do-github-fatiado-em-chunks.md)).
- Autenticação como GitHub App: JWT `RS256` assinado com a chave privada do App e installation
  token cacheado por instalação, renovado 5 minutos antes de vencer
  ([ADR-006](docs/adr/006-token-de-instalacao-do-github-app-com-cache-em-memoria.md)).
- PR sem arquivo revisável publica `pr.diff.ready` com a lista de chunks vazia, em vez de não
  publicar: a review termina aprovada e barata, em vez de ficar pendurada até o timeout do
  `orchestrator`.
- O `diff-fetcher` distingue erro que vale retentar de erro que não vale: rate limit e `5xx` voltam
  para o retry da fila; PR apagado, App sem permissão e credencial inválida vão direto para a DLQ,
  em vez de insistir três vezes num erro que não muda.
- Idempotência do `webhook-gateway` por `X-GitHub-Delivery`: a entrega é reservada no Postgres com
  `INSERT ... ON CONFLICT DO NOTHING` **antes** de publicar `pr.review.requested`, na mesma
  transação, então uma reentrega do GitHub não gera review duplicada — nem as três chamadas de LLM
  que ela custaria ([ADR-005](docs/adr/005-idempotencia-do-webhook-por-delivery-id-no-postgres.md)).
- Expurgo agendado dos registros de idempotência, com retenção de 30 dias configurável em
  `gateway.idempotency`. Passada a retenção, uma reentrega manual pelo painel do App volta a gerar
  review, que é o comportamento desejado.
- Camadas `domain`, `application` e `infrastructure` no `webhook-gateway`, com as portas
  `ProcessedDeliveries` e `ReviewRequestPublisher` declaradas pelo caso de uso.
- Testes de integração com Testcontainers: reserva do mesmo delivery id em paralelo, convivência dos
  históricos do Flyway em schemas separados, idempotência ponta a ponta pelo HTTP e rollback da
  reserva quando a publicação falha.
- Maven Failsafe no build — sem ele os testes `*IT` compilavam e nunca rodavam.

### Changed

- Payload de `pull_request` incompleto passa a responder `400` em vez de publicar. Faltando
  `installation.id`, owner, repo, número do PR ou `headSha`, o evento publicado quebraria o
  `diff-fetcher` depois de o sistema já ter pago o diff e as chamadas de LLM.
- Dependabot passa a ignorar majors do Spring Boot e do Java nas imagens Docker; essas subidas são
  decisão de projeto, com ADR, e não bump automático.

### Fixed

- **Spring AI preso na linha 1.0.x.** A versão 2.0.1 declarada no POM exige Spring Framework 7, que
  vem com o Spring Boot 4. Com o Boot 3.5 o `review-agent` compilava e estourava
  `NoClassDefFoundError` em `org.springframework.core.Nullness` na primeira chamada ao modelo —
  ninguém tinha notado porque nenhum teste exercitava esse caminho antes da fase 4. Subir junto com
  o Boot 4 é decisão de projeto, com ADR.
- `errorMessage` do `AgentCompletedEvent` passa a carregar só o tipo da exceção, nunca a mensagem
  dela: o campo é gravado pelo `orchestrator` e pode chegar ao PR, e a mensagem de um cliente de LLM
  costuma trazer de volta o corpo da requisição — que é o código do pull request. O detalhe completo
  fica no log do agente.
- Corpo de webhook que não era JSON válido respondia `500`, e o GitHub reentregava indefinidamente
  um corpo que nunca ia parsear. Agora responde `400`, e a mensagem registrada em log é de produção
  própria: a do Jackson carrega um trecho do corpo cru, que não pode ir para log.
- `mvnw` e os hooks em `.claude/hooks/` estavam versionados sem bit de execução, o que fazia o CI
  falhar com `Permission denied` no Linux.

## [0.1.0] - 2026-09-28

### Added

- Estrutura de módulos Maven multi-módulo (`common`, `webhook-gateway`, `diff-fetcher`, `review-agent`, `orchestrator`, `github-publisher`).
- Contratos de eventos (`ReviewRequestedEvent`, `DiffReadyEvent`, `AgentCompletedEvent`, `ReviewReadyEvent`) e topologia RabbitMQ em `common`.
- `SignatureVerifier` com comparação HMAC em tempo constante no `webhook-gateway`.
- Modelo de dados inicial do `orchestrator` (Flyway `V1__create_review_tables.sql`).
- Padrões de código: Spotless (google-java-format), JaCoCo (mínimo 80% em `domain`/`application`), ArchUnit para isolar a camada `domain`.
- Pacotes `domain`, `application` e `infrastructure` em cada serviço, com as regras de camada validadas por `ArchitectureTest`.
- CI roda `spotless:check`, testes, ArchUnit e o gate de cobertura.
- Documentação viva: `CLAUDE.md` e ADRs 001–004 em `docs/adr/`.
- Subagentes e skills do Claude Code para o fluxo de desenvolvimento das próximas fases.
- Templates de PR, issues e configuração do Dependabot.

### Fixed

- `review-agent` não compilava: faltava a dependência `spring-boot-starter-validation` usada por `AgentProperties`.
