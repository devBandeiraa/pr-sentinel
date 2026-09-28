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

Para criar a próxima, use a skill `write-adr`.
