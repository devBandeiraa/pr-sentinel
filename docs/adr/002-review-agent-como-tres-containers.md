# ADR-002 — Um módulo `review-agent` executado como três containers

- **Status:** Aceita
- **Data:** 2026-09-28

## Contexto

O sistema tem três agentes de revisão: segurança, performance e padrões. Os três fazem exatamente o
mesmo trabalho mecânico — consumir `pr.diff.ready`, montar o prompt, chamar o LLM, validar a saída,
filtrar por confiança e publicar `pr.agent.completed`. O que muda entre eles é o **prompt** e a
**fila** que consomem.

O roadmap prevê novos tipos de agente. O custo de adicionar um agente é um critério de projeto.

## Decisão

Manter **um único módulo Maven** (`review-agent`) e executá-lo como **três containers**, cada um com
a variável de ambiente `AGENT_TYPE` diferente (`SECURITY`, `PERFORMANCE`, `STANDARDS`).

`AgentProperties` deriva de `AGENT_TYPE`:

- o nome da fila (`pr-sentinel.review-agent-security`, etc.), via `serviceName()`;
- o prompt carregado (`classpath:prompts/security.st`, etc.), via `promptResource()`.

Cada container declara sua própria fila ligada à routing key `pr.diff.ready`, produzindo o fan-out.

Adicionar um agente novo passa a ser: um valor no enum `AgentType`, um arquivo em
`src/main/resources/prompts/`, um serviço no `docker-compose.yml` e casos de eval. Nenhum módulo
novo, nenhuma duplicação de código. O passo a passo está na skill `add-review-agent`.

## Alternativas consideradas

**Um módulo Maven por agente.** Daria isolamento máximo, mas triplicaria o código de infraestrutura
(listener, publisher, tratamento de erro, configuração) e faria qualquer correção nesse código
precisar de três edições idênticas. O ganho de isolamento é ilusório: os três têm a mesma lógica.

**Um único container com os três agentes em threads.** Menos containers para operar, mas o LLM é a
parte lenta e cara: um agente travado consumiria o pool compartilhado e atrasaria os outros. Também
impede escalar os agentes de forma independente (segurança costuma ter mais volume de achados) e
volta a acoplar as falhas, contrariando a [ADR-001](001-arquitetura-orientada-a-eventos-com-rabbitmq.md).

**Um agente só, com um prompt que cobre os três assuntos.** Mais barato em tokens, mas a qualidade
cai: prompts longos e multiobjetivo diluem o foco e aumentam falso positivo. Também impossibilita
usar modelos ou temperaturas diferentes por especialidade.

## Consequências

**Positivas**

- Uma correção no fluxo de análise vale para os três agentes.
- Escala e falha de forma independente por tipo de agente.
- Novo agente = prompt + container; sem código novo.
- Permite modelo, temperatura e limiar de confiança distintos por container, só mudando
  configuração.

**Negativas**

- `AGENT_TYPE` vira configuração obrigatória: subir o container sem ela é erro de inicialização
  (garantido por `@NotNull` em `AgentProperties`).
- A imagem carrega os três prompts, mesmo usando um só. Irrelevante em tamanho.
- O nome da fila é resolvido por SpEL (`@RabbitListener(queues = "#{@agentQueueName}")`), o que é
  menos óbvio de ler do que uma constante — daí o bean `agentQueueName` existir com esse nome
  explícito.
- Os evals precisam rodar por tipo de agente, não uma vez só.
