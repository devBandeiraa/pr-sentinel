# Architecture Decision Records

Decisões estruturais do PR Sentinel, em ordem de numeração. Uma ADR registra o contexto em que a
decisão foi tomada — ela não é atualizada quando a decisão muda; nesse caso cria-se uma nova ADR que
supersede a anterior.

| # | Decisão | Status |
|---|---|---|
| [001](001-arquitetura-orientada-a-eventos-com-rabbitmq.md) | Arquitetura orientada a eventos com RabbitMQ | Aceita |
| [002](002-review-agent-como-tres-containers.md) | Um módulo `review-agent` executado como três containers | Aceita |
| [003](003-review-parcial-em-timeout-de-agente.md) | Review parcial em caso de timeout de agente | Aceita |
| [004](004-saida-estruturada-do-llm-com-limiar-de-confianca.md) | Saída estruturada do LLM com limiar de confiança | Aceita |
| [005](005-idempotencia-do-webhook-por-delivery-id-no-postgres.md) | Idempotência do webhook por delivery id em tabela no Postgres | Aceita |
| [006](006-token-de-instalacao-do-github-app-com-cache-em-memoria.md) | Token de instalação do GitHub App obtido sob demanda e cacheado em memória | Aceita |
| [007](007-diff-por-arquivo-da-api-do-github-fatiado-em-chunks.md) | Diff obtido arquivo por arquivo pela API do GitHub e fatiado em fronteira de hunk | Aceita |
| [008](008-consolidacao-por-arquivo-e-linha-com-veredito-por-severidade.md) | Consolidação deduplica por arquivo e linha, e o veredito sai da severidade máxima | Aceita |

Para criar a próxima, use a skill `write-adr`.
