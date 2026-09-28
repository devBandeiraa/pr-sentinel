---
name: add-review-agent
description: Use quando for adicionar um novo tipo de agente de revisão ao PR Sentinel (por exemplo acessibilidade, testes ou documentação). Cobre o valor no enum AgentType, o prompt, o container no docker-compose, os casos de eval e a atualização do README.
---

# Adicionar um novo agente de revisão

Pela [ADR-002](../../../docs/adr/002-review-agent-como-tres-containers.md), um agente novo é
**prompt + container**. Nenhum módulo novo, nenhum código de infraestrutura duplicado. Se você se
pegar escrevendo uma classe nova para isso, pare — provavelmente está no caminho errado.

## Antes: vale a pena?

Um agente a mais é uma chamada de LLM a mais por chunk, em custo e em latência. Responda antes:

- O assunto é **distinto** dos três existentes? Se um prompt atual pode cobrir com um item na lista,
  faça isso.
- Existe volume real de achados? Agente que quase nunca acha nada só gasta token.
- Dá para descrever o foco em uma frase, com exclusões claras? Se não, o prompt vai ficar difuso e
  gerar falso positivo.

## Passos

### 1. Valor no enum

`common/src/main/java/dev/bandeira/prsentinel/common/event/AgentType.java`:

```java
public enum AgentType {
  SECURITY,
  PERFORMANCE,
  STANDARDS,
  ACCESSIBILITY
}
```

Adicionar valor a enum é compatível **se** todo consumidor tratar valor desconhecido — confira os
pontos que fazem `switch` ou `valueOf` sobre `AgentType`. Veja a skill `event-contract`.

`AgentProperties` deriva o resto sozinho: a fila vira `pr-sentinel.review-agent-accessibility` e o
prompt vira `classpath:prompts/accessibility.st`. Não escreva isso à mão.

### 2. Prompt

Crie `review-agent/src/main/resources/prompts/<tipo-minúsculo>.st`. O nome do arquivo **tem que**
ser o enum em minúsculas — é assim que `AgentProperties.promptResource()` encontra.

Siga a estrutura dos existentes: papel, foco exclusivo, lista do que procurar, regras. Obrigatório:

- diga explicitamente **o que ignorar** (os assuntos dos outros agentes);
- ancore cada nível de severidade neste domínio;
- explique as faixas de `confidence`;
- **"Se não houver problemas, devolva a lista de achados vazia"** — sem isso o modelo inventa achado;
- "Na dúvida, não reporte";
- só linhas com prefixo `+`, numeração do arquivo novo;
- `title`, `explanation` e `suggestion` em português.

Escreva o prompt com o subagente `prompt-engineer`.

### 3. Container

Em `docker-compose.yml`, um serviço novo usando a âncora `*agent`:

```yaml
  agent-accessibility:
    <<: *agent
    environment:
      <<: *rabbit-env
      AGENT_TYPE: ACCESSIBILITY
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      LLM_MODEL: ${LLM_MODEL:-claude-sonnet-5}
```

A âncora já traz build, `depends_on` e `restart`. A fila e o binding a `pr.diff.ready` são
declarados pelo próprio serviço ao subir (`ReviewAgentApplication.agentQueue`).

Se o agente precisar de porta exposta, use `SERVER_PORT` — as portas 8082+ já estão em uso pelos
outros.

### 4. Orchestrator

O orchestrator espera **todos** os agentes antes de consolidar. Confira como ele descobre quantos
são: se a contagem for fixa em código ou em configuração, atualize — senão a review vai ficar
esperando para sempre um quarto agente que ele não sabe que existe, ou fechar cedo demais.

Reveja também o timeout (ADR-003): mais um agente em paralelo pode mudar a latência total.

### 5. Evals

Agente sem eval não entra. Crie os casos do tipo novo antes de considerar pronto:

- diffs que **contêm** o problema que o agente deve achar (verdadeiro positivo);
- diffs limpos, onde ele **não** pode achar nada (falso positivo);
- diffs com problemas dos **outros** agentes, que ele deve ignorar (teste de foco).

Rode com a skill `run-evals` e registre a linha de base.

### 6. Documentação

- `README.md`: cite o agente novo na descrição e no diagrama Mermaid (nó novo consumindo
  `pr.diff.ready` e publicando `pr.agent.completed`).
- `CHANGELOG.md`, em `[Unreleased]` / `Added`.
- `CLAUDE.md`, se a tabela de módulos precisar de ajuste.

### 7. Testes

- Teste que o prompt do tipo novo carrega (`AgentProperties.promptResource()` resolve).
- Teste de integração do consumo com Testcontainers e LLM em WireMock — **nunca** API real.

## Checklist

- [ ] Valor adicionado a `AgentType`, consumidores conferidos
- [ ] Prompt em `prompts/<tipo>.st`, nome batendo com o enum em minúsculas
- [ ] Prompt autoriza lista vazia e diz o que ignorar
- [ ] Serviço no `docker-compose.yml` com `AGENT_TYPE`
- [ ] Orchestrator sabe contar o agente novo
- [ ] Casos de eval: verdadeiro positivo, falso positivo e foco
- [ ] Evals rodados, linha de base registrada
- [ ] README (texto + diagrama), CHANGELOG e CLAUDE.md atualizados
- [ ] `./mvnw verify` verde
