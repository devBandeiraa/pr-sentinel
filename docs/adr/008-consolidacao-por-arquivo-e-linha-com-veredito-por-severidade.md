# ADR-008 — Consolidação deduplica por arquivo e linha, e o veredito sai da severidade máxima

- **Status:** Aceita
- **Data:** 2026-10-19

## Contexto

O `orchestrator` recebe um `pr.agent.completed` de cada um dos três agentes e precisa transformar
três listas de achados em **uma** review. Duas perguntas ficaram abertas desde a fase 1, quando
`ReviewReadyEvent` foi desenhado com os campos `verdict` e `findings`:

**Os agentes se sobrepõem.** Eles têm focos diferentes (segurança, performance, padrões), mas o
mesmo trecho de código costuma atrair mais de um. Uma query montada por concatenação é SQL injection
para o agente de segurança e violação de camada para o de padrões. Sem deduplicação, o autor do PR
recebe dois comentários na mesma linha dizendo coisas parecidas — e foi exatamente por isso que a
[ADR-003](003-review-parcial-em-timeout-de-agente.md) descartou publicar uma review por agente.

**`Verdict` não tinha regra.** O enum existe (`APPROVED`, `ATTENTION`, `CHANGES_REQUESTED`) e nada
dizia como chegar nele.

O que já é verdade no sistema e restringe as duas respostas:

- Cada achado carrega `file`, `line`, `severity` e `confidence`, e os achados que chegam ao
  `orchestrator` já passaram pelo limiar e pela validação de forma do agente
  ([ADR-004](004-saida-estruturada-do-llm-com-limiar-de-confianca.md)). Aqui não se valida de novo.
- Na fase 6 cada achado vira um comentário inline no PR, posicionado por arquivo e linha.
- A review pode ser parcial (ADR-003), então a consolidação precisa funcionar com um, dois ou três
  agentes.

## Decisão

**A chave de deduplicação é `file` + `line`.** No máximo um achado por linha de arquivo chega ao PR.

Quando mais de um achado cai na mesma chave, vence o de **maior severidade**; empate de severidade
decide pela **maior confiança**; empate nos dois mantém o primeiro, na ordem em que os agentes
responderam. Os descartados não vão para `ReviewReadyEvent`, mas continuam gravados em
`finding`, ligados ao `agent_result` do agente que os reportou — o dado não se perde, só não vira
comentário.

**O veredito sai da severidade máxima** dos achados consolidados:

| Pior severidade presente | Veredito |
|---|---|
| `CRITICAL` ou `HIGH` | `CHANGES_REQUESTED` |
| `MEDIUM` | `ATTENTION` |
| `LOW`, `INFO`, ou nenhum achado | `APPROVED` |

**Review parcial calcula o veredito com o que tem**, e `partial` mais `missingAgents` dizem o que
faltou. Um PR sem nenhum achado e sem nenhum agente faltando é `APPROVED` de verdade; o mesmo PR com
o agente de segurança ausente é `APPROVED` **parcial**, e quem lê a review vê a diferença.

**Os achados vão ordenados** por severidade (mais grave primeiro), depois por arquivo e por linha —
para o comentário da fase 6 começar pelo que importa.

**A publicação é condicional a uma transição de estado no banco.** Publicar acontece só quando o
`UPDATE` de `review` para `COMPLETED` ou `PARTIAL` afeta uma linha, partindo de `PENDING` ou
`IN_PROGRESS`. Sem isso, o terceiro `pr.agent.completed` e a varredura de timeout podem consolidar a
mesma review ao mesmo tempo e publicar dois `pr.review.ready` — dois comentários no mesmo PR. É o
mesmo padrão da [ADR-005](005-idempotencia-do-webhook-por-delivery-id-no-postgres.md): quem decide é
a condição no banco, não uma consulta prévia.

## Alternativas consideradas

**Deduplicar por `file` + `line` + `severity`.** Só colapsa quando dois agentes concordam na
gravidade. Descartada porque o caso comum é justamente o contrário: o mesmo problema visto por dois
focos recebe severidades diferentes, e o resultado prático seria quase nenhuma deduplicação — o PR
voltaria a receber comentários repetidos, que é o problema que esta ADR existe para resolver.

**Deduplicar por `file` + `line` juntando as explicações.** O achado mais severo levaria título e
severidade, e as explicações dos outros entrariam no corpo, com o agente que reportou. Nada se
perderia. Descartada por agora porque monta um campo `explanation` por concatenação, o que deixa o
comentário longo e de autoria confusa — e porque a informação não se perde de fato: ela continua em
`finding` no banco. Se a métrica da fase 7 mostrar que descartar está escondendo achado útil, esta é
a alternativa a reabrir, com ADR nova.

**Não deduplicar entre agentes, só dentro de cada um.** Zero risco de perder achado distinto.
Descartada pelo motivo que a ADR-003 já tinha registrado: comentário repetido na mesma linha é o
tipo de ruído que faz o time desligar o bot.

**Deduplicar por semelhança de título.** Jaccard sobre os tokens, ou pedir ao LLM para agrupar.
Descartada por ser fuzzy num lugar que precisa ser determinístico: o resultado mudaria com o
limiar escolhido, ficaria difícil de testar e, no caso do LLM, somaria uma quarta chamada paga só
para organizar o que já está pronto.

**Veredito pela contagem de achados** (por exemplo, `CHANGES_REQUESTED` acima de cinco). Descartada
porque trata gravidade e volume como a mesma coisa: um SQL injection isolado pesa mais que oito
sugestões de nomenclatura, e a contagem inverteria essa relação.

**Veredito ponderado por confiança.** Severidade vezes confiança, com faixas. Descartada por somar
um segundo número arbitrário ao limiar da ADR-004 — o achado já passou pelo corte de confiança para
chegar até aqui, e ponderar de novo seria aplicar o mesmo critério duas vezes.

## Consequências

**Positivas**

- No máximo um comentário por linha, com a severidade mais alta que alguém atribuiu àquele ponto.
- O veredito é determinístico e explicável em uma frase, o que importa quando alguém discorda dele.
- Nada é perdido no banco: `finding` guarda todos os achados por agente, inclusive os descartados na
  consolidação, e a fase 7 pode medir quanto a deduplicação está removendo.
- A consolidação funciona igual para review completa e parcial, então o caminho de timeout não é um
  fluxo separado.
- Publicação exatamente uma vez por review, garantida por condição no banco e não por ordem de
  mensagens.

**Negativas**

- **Dois problemas distintos na mesma linha colapsam em um.** Fica o mais severo e o outro não chega
  ao PR. É a troca assumida por não poluir a conversa; o descartado continua no banco.
- A ordem de chegada dos agentes decide o empate duplo (mesma severidade e mesma confiança), o que
  torna o resultado dependente de corrida nesse caso específico. Como os três campos restantes são
  iguais, o impacto é escolher entre dois achados equivalentes.
- O mapa de severidade para veredito é um palpite calibrado: `HIGH` pedindo mudança pode ser
  agressivo em repositório legado. É configuração que a fase 7 deve revisitar com dados.
- O `orchestrator` passa a depender de uma transição condicional correta. Um `UPDATE` sem a cláusula
  de status reintroduz a publicação dupla sem que nenhum teste de unidade perceba — por isso existe
  teste de integração com duas threads disputando a mesma review.
