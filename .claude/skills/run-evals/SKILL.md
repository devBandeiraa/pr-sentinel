---
name: run-evals
description: Use para rodar os evals dos prompts dos agentes de revisão e comparar o resultado com a execução anterior. Obrigatória antes e depois de qualquer alteração de prompt ou do limiar de confiança.
---

# Rodar e comparar evals

Evals medem a qualidade dos prompts. Sem eles, "melhorei o prompt" é chute — o modelo não avisa
quando ficou pior, e a regressão só aparece como falso positivo no PR de alguém.

> **Estado atual:** o harness de evals está previsto para a **fase 7** do roadmap e ainda não
> existe. Esta skill define onde ele mora e qual é o protocolo. Ao construí-lo, siga esta estrutura
> em vez de inventar outra — e atualize esta skill com o comando real.

## Onde as coisas ficam

```
review-agent/src/test/resources/evals/
  cases/<tipo>/<nome-do-caso>.json    # diff de entrada + achados esperados
  baselines/<tipo>-<AAAA-MM-DD>.json  # resultado de referência
```

Um caso descreve: o diff, o `AgentType`, os achados esperados (arquivo, linha, severidade) e se é
caso positivo, negativo ou de foco.

## Tipos de caso — os três são obrigatórios

- **Verdadeiro positivo** — o diff contém o problema; o agente precisa achar. Mede recall.
- **Falso positivo** — o diff está limpo; o agente **não** pode achar nada. Mede precisão. É o caso
  mais importante: pela [ADR-004](../../../docs/adr/004-saida-estruturada-do-llm-com-limiar-de-confianca.md),
  falso positivo custa mais que falso negativo.
- **Foco** — o diff contém problemas **dos outros** agentes; este deve ignorá-los. Protege a
  separação da [ADR-002](../../../docs/adr/002-review-agent-como-tres-containers.md).

## Protocolo

**Nunca altere um prompt sem rodar os evals antes e depois.**

### 1. Linha de base — antes de mexer em qualquer coisa

```bash
./mvnw -pl review-agent test -Dtest=EvalRunner -Dagent.type=SECURITY
```

Guarde o resultado em `baselines/`. Se já existe uma baseline recente do prompt **atual e não
modificado**, pode usá-la — confira que o prompt não mudou desde então (`git log` no arquivo `.st`).

Requer `ANTHROPIC_API_KEY` no ambiente. Este é o **único** lugar do projeto que chama o LLM de
verdade; todo o resto usa WireMock.

### 2. Uma mudança de cada vez

Duas alterações no mesmo ciclo e você não sabe qual causou o efeito. Se quiser testar duas ideias,
são dois ciclos.

### 3. Rodar de novo

Mesmo comando, mesmos casos, mesmo modelo. Trocar o modelo no meio invalida a comparação.

### 4. Comparar

Reporte lado a lado, sempre com números:

| Métrica | Antes | Depois |
|---|---|---|
| Verdadeiros positivos encontrados | | |
| Falsos positivos | | |
| Precisão | | |
| Recall | | |
| Violações de foco | | |
| `confidence` média dos corretos | | |
| `confidence` média dos incorretos | | |
| Achados descartados pelo limiar | | |
| Tokens de entrada / saída (média) | | |
| Latência média | | |

Olhe também a **distribuição de `confidence`**: se a média dos achados errados subiu, o limiar de
0.6 perdeu eficácia — isso é uma regressão mesmo que a precisão pareça estável.

### 5. Decidir

- **Melhorou** — precisão subiu sem derrubar recall, ou recall subiu sem gerar falso positivo:
  mantenha e grave a baseline nova.
- **Empatou** — reverta. Prompt maior sem ganho é só mais token por chamada.
- **Piorou** — reverta e registre no relatório o que não funcionou, para ninguém tentar de novo.

Por ser não determinístico, diferença de um caso isolado pode ser ruído. Rode de novo antes de
concluir a partir de uma diferença pequena.

## Cuidados

- Rode por **tipo de agente**. Um prompt não afeta os outros (ADR-002), mas a comparação é por tipo.
- Fixe `temperature` em 0.1, como em produção.
- Casos de eval **não contêm segredo real** — se um caso precisa exercitar detecção de segredo,
  use valor claramente falso.
- Custo é real: cada execução gasta tokens. Não rode em loop nem no CI a cada push.
- Ao mudar `agent.min-confidence`, rode evals também — o limiar muda o resultado sem que o prompt
  mude.

## Checklist

- [ ] Baseline do prompt atual registrada antes da mudança
- [ ] Uma alteração por ciclo
- [ ] Mesmo modelo, temperatura e casos nas duas execuções
- [ ] Os três tipos de caso presentes
- [ ] Comparação com números, não impressão
- [ ] Distribuição de `confidence` conferida
- [ ] Baseline nova gravada, se a mudança foi mantida
- [ ] Números anexados à descrição do PR
