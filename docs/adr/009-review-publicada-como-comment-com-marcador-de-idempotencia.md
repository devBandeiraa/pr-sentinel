# ADR-009 — Review publicada como `COMMENT`, com marcador de idempotência no corpo

- **Status:** Aceita
- **Data:** 2026-10-19

## Contexto

A fase 6 fecha o fluxo: o `github-publisher` pega o `pr.review.ready` que o `orchestrator` publica e
transforma em uma review de verdade no Pull Request. É o primeiro ponto do sistema que **escreve** no
repositório de alguém, e isso muda o peso dos erros — até aqui, falha significava review que não
acontece; daqui em diante pode significar comentário errado, repetido ou que bloqueia o trabalho do
time.

Quatro fatos restringem as decisões:

- A API de review do GitHub (`POST /repos/{owner}/{repo}/pulls/{number}/reviews`) aceita um `event`:
  `COMMENT`, `REQUEST_CHANGES` ou `APPROVE`. Com `REQUEST_CHANGES`, um repositório com branch
  protection **bloqueia o merge** até alguém dar dismiss na review — e dismiss exige permissão.
- Comentário inline precisa apontar para uma linha **que faça parte do diff**. A linha de um
  `Finding` vem do LLM, e nada garante que ela esteja no diff. Quando uma só está fora, o GitHub
  recusa a requisição inteira com `422` e **nenhum** comentário é postado.
- A fila entrega ao menos uma vez. O `orchestrator` garante um `pr.review.ready` por review
  ([ADR-008](008-consolidacao-por-arquivo-e-linha-com-veredito-por-severidade.md)), mas uma falha
  entre postar no GitHub e confirmar a mensagem faz a reentrega postar de novo. Seria o único buraco
  de comentário duplicado que sobrou na cadeia.
- O `github-publisher` precisa do mesmo installation token do `diff-fetcher`
  ([ADR-006](006-token-de-instalacao-do-github-app-com-cache-em-memoria.md)), que registrou a
  duplicação como esperada e deixou a decisão de extrair para esta fase.

Vale lembrar o que os achados são: saída de LLM filtrada por confiança mínima de 0.6
([ADR-004](004-saida-estruturada-do-llm-com-limiar-de-confianca.md)). São úteis e **não são
verdade**.

## Decisão

**A review é postada como `COMMENT` por padrão.** `publisher.request-changes` existe e vem `false`;
quando ligada, veredito `CHANGES_REQUESTED` passa a postar `REQUEST_CHANGES`. A política fica com
quem instala o App, não embutida no código, e o default é o que não bloqueia ninguém.

**`APPROVE` nunca é usado.** Um bot aprovando dá a impressão de revisão humana feita, e o pior caso
de `COMMENT` ("ninguém leu") é melhor que o pior caso de `APPROVE` ("todos pensaram que alguém leu").
Veredito `APPROVED` sai como `COMMENT` dizendo que nada foi encontrado.

**Idempotência por marcador no corpo da review.** O resumo termina com um comentário HTML invisível
no PR:

```html
<!-- pr-sentinel:review-id=4f8c... -->
```

Antes de postar, o serviço busca as reviews existentes do PR e procura o marcador do `reviewId`
corrente. Se achar, não posta. Uma requisição `GET` a mais por publicação, nenhuma infraestrutura
nova, e o GitHub — que é a fonte da verdade sobre o que está no PR — passa a ser também o registro de
idempotência.

**Comentário inline recusado cai para review sem inline.** A primeira tentativa vai com os
comentários posicionados. Se o GitHub devolver `422`, o serviço posta a mesma review só com o resumo,
agora com os achados listados no corpo por `arquivo:linha`. Uma review menos navegável é muito melhor
que nenhuma review.

**O token é duplicado, não extraído.** `InstallationTokenProvider`, `AppJwtFactory` e
`PrivateKeyLoader` são copiados do `diff-fetcher`. O `common` continua sendo só contratos de evento,
topologia e regras de arquitetura. O gatilho para extrair é um **terceiro** consumidor, e quando isso
acontecer o destino é um módulo próprio, não o `common`.

**Rate limit usa o retry da fila.** `403` com `x-ratelimit-remaining: 0` e `429` sobem como exceção,
e a fila retenta cinco vezes com backoff dobrando a partir de 2s. O resto dos `4xx` vai direto para a
DLQ, como no `diff-fetcher`.

## Alternativas consideradas

**`REQUEST_CHANGES` quando o veredito pedir mudança.** Sinal forte e inequívoco. Descartada como
padrão porque transforma falso positivo de LLM em time parado: um achado de confiança 0.61 travaria o
merge até alguém com permissão dar dismiss. Pela ADR-004, falso positivo custa mais que falso
negativo — e bot que atrapalha é bot que alguém desliga. Continua disponível por configuração.

**Comentário inline por achado, em requisições separadas** (`POST .../pulls/{n}/comments`).
Resolveria o `422` em bloco: cada comentário fora do diff falharia sozinho. Descartada por dois
motivos: N requisições em vez de uma, o que encosta no rate limit secundário justamente nos PRs
grandes, e o resultado não aparece como uma review única no PR — viram comentários soltos, sem
veredito e sem o resumo que dá contexto.

**Buscar o diff do PR para validar as linhas antes de postar.** Eliminaria o `422` por completo.
Descartada por reimplementar no `github-publisher` o parser de diff que a
[ADR-007](007-diff-por-arquivo-da-api-do-github-fatiado-em-chunks.md) já fez no `diff-fetcher`, e por
uma requisição a mais em **toda** publicação para evitar um erro que acontece em algumas.

**Tabela de idempotência no Postgres, como no gateway.** Seria coerente com a ADR-005. Descartada
porque daria banco ao único serviço que ainda não tem — com healthcheck, migration e modo de falha
próprios — para guardar o que o próprio GitHub já sabe responder. O marcador resolve com um `GET`.

**Idempotência pelo `headSha` em vez do `reviewId`.** Evitaria também reviews duplicadas vindas de
dois eventos diferentes para o mesmo commit. Descartada porque é a semântica errada: um `synchronize`
que não muda o `headSha` ainda merece review nova, e o `reviewId` é exatamente a unidade que o resto
do sistema usa.

**Extrair o cliente do GitHub para o `common`.** Zero duplicação. Descartada porque o `common` é
dependência de todos os seis módulos, e o `review-agent` e o `orchestrator` passariam a arrastar
HTTP e carregamento de chave privada sem nunca falar com o GitHub. Com dois consumidores, duplicar
sessenta linhas custa menos que acoplar seis módulos.

## Consequências

**Positivas**

- O bot nunca bloqueia merge na configuração padrão; quem quiser o modo duro opta por ele.
- Fecha o último buraco de comentário duplicado da cadeia, e fecha sem infraestrutura nova.
- Achado com linha fora do diff degrada a review em vez de perdê-la.
- O `common` continua sem conhecer HTTP, então o `review-agent` e o `orchestrator` seguem leves.
- O marcador no corpo também serve para depurar: dá para achar no PR qual `reviewId` gerou aquele
  comentário.

**Negativas**

- **Uma requisição `GET` a mais por publicação**, inclusive nas que não são reentrega. É o custo da
  idempotência sem banco.
- A janela de duplicação não fecha de todo: duas réplicas do publisher processando a mesma reentrega
  ao mesmo tempo podem passar as duas pela checagem antes de qualquer uma postar. Com uma réplica e
  reentrega rara, é aceitável; com mais réplicas, volta a ser problema.
- **O marcador é frágil a edição**: se alguém editar o corpo da review no PR e apagar o comentário
  HTML, a reentrega posta de novo.
- `publisher.request-changes` é um knob que muda comportamento visível para o time do repositório, e
  ninguém vai lembrar que ele existe. Precisa estar no README, não só no `application.yml`.
- A duplicação do cliente do GitHub é dívida assumida: um ajuste no fluxo de token agora precisa ser
  feito em dois lugares, e nada no build avisa se só um foi alterado.
- Honrar `Retry-After` exatamente fica de fora: o backoff da fila soma cerca de 30 segundos em cinco
  tentativas, e o rate limit secundário do GitHub costuma pedir 60. Nesse caso a mensagem vai para a
  DLQ e a review precisa ser reprocessada à mão.
