# ADR-007 — Diff obtido arquivo por arquivo pela API do GitHub e fatiado em fronteira de hunk

- **Status:** Aceita
- **Data:** 2026-10-19

## Contexto

O `diff-fetcher` é o serviço que transforma "existe um PR para revisar" em "aqui está o código a
revisar". O que ele publica em `pr.diff.ready` é o insumo exato dos três agentes, e cada chunk
publicado vira **três chamadas de LLM** — uma por agente, pelo fan-out da
[ADR-001](001-arquitetura-orientada-a-eventos-com-rabbitmq.md). O formato do que ele publica não é
detalhe interno: é o que define custo e qualidade da review.

O contrato já existia desde a fase 1, em `DiffReadyEvent.DiffChunk`, com os campos `file`,
`language`, `chunkIndex` e `patch`. A fase 3 precisa preenchê-lo.

Quatro fatos do ambiente restringem a solução:

- Um PR pode ter centenas de arquivos, e parte deles não é código que valha revisar: lockfile,
  arquivo gerado, minificado, binário. O `application.yml` do `diff-fetcher` já previa
  `diff.ignored-patterns` e `diff.max-chunk-chars: 12000` desde a fase 1.
- O LLM tem janela de contexto finita, e um prompt gigante piora a review mesmo quando cabe: o
  modelo dilui a atenção e passa a devolver achado genérico.
- Um diff unificado tem estrutura. Ele é dividido em *hunks*, cada um começando com `@@ -a,b +c,d @@`
  e carregando o contexto de linhas ao redor da mudança. Cortar no meio de um hunk entrega ao agente
  um trecho sem o cabeçalho que diz onde aquilo fica no arquivo.
- `Finding` tem `file` e `line`, e na fase 6 isso vira comentário inline no PR. Se o chunk não
  preserva a informação de posição, o comentário cai na linha errada.

## Decisão

O `diff-fetcher` busca `GET /repos/{owner}/{repo}/pulls/{number}/files`, paginado com
`per_page=100`, e trabalha **arquivo por arquivo** com o campo `patch` que a própria API devolve.

**Arquivo entra na review só se tiver patch, não tiver sido removido e não casar com
`diff.ignored-patterns`.** As três exclusões têm motivos diferentes:

- sem `patch` é binário ou arquivo grande demais — o GitHub omite o campo, e não há texto a revisar;
- `status: removed` é código que deixou de existir, e achado sobre linha apagada é ruído;
- os globs (`**/package-lock.json`, `**/*.min.js`, `**/generated/**`) são arquivos que mudam muito e
  nunca merecem opinião de revisor.

O casamento de glob usa `FileSystems.getDefault().getPathMatcher("glob:...")`, do JDK, e não
`AntPathMatcher` do Spring: o filtro é regra de negócio e vive no `domain`, onde framework não entra.

**O fatiamento acontece em fronteira de hunk.** `DiffChunker` acumula hunks inteiros enquanto couber
em `max-chunk-chars` e fecha o chunk antes de estourar. Hunk que sozinho passa do limite é quebrado
por linha, nunca no meio de uma linha, e os pedaços seguintes recebem o cabeçalho `@@` do hunk
original repetido — sem isso o agente perde a referência de posição e o `line` do achado fica
inútil.

**`chunkIndex` é sequencial por arquivo**, começando em zero, e `language` sai da extensão por um
mapa simples no `domain`. Extensão desconhecida vira `"text"`, não erro: linguagem é dica para o
prompt, não condição para revisar.

**PR sem nenhum arquivo revisável publica `pr.diff.ready` com a lista de chunks vazia**, em vez de
não publicar nada. É a diferença entre uma review que termina aprovando e uma review que fica
pendurada até o timeout do `orchestrator` ([ADR-003](003-review-parcial-em-timeout-de-agente.md)). Os
agentes recebem zero chunks, não chamam o LLM e respondem `SUCCESS` sem achados.

## Alternativas consideradas

**Buscar o diff cru em `GET /repos/{owner}/{repo}/pulls/{number}` com
`Accept: application/vnd.github.v3.diff`.** Uma requisição só, e o diff completo de uma vez.
Descartada porque devolve um texto único que precisaria ser parseado à mão para saber onde um arquivo
termina e o outro começa — reescrever um parser de diff unificado para obter exatamente o que o
endpoint `/files` já entrega estruturado, com `filename` e `patch` separados. O ganho de uma
requisição não paga um parser caseiro no caminho crítico.

**Mandar o PR inteiro num chunk só, sem fatiar.** Simplifica o `diff-fetcher` a quase nada e dá ao
agente o contexto completo de uma vez. Descartada por dois motivos: PR grande estoura a janela de
contexto e falha inteiro em vez de falhar em parte, e review de prompt gigante degrada — o modelo
devolve comentário genérico sobre o conjunto em vez de achado específico com arquivo e linha.

**Um chunk por arquivo, sem limite de tamanho.** Resolve a maior parte dos casos, porque a maioria
dos arquivos é pequena. Descartada porque o caso que importa é justamente o contrário: o arquivo de
2.000 linhas alteradas é o que mais precisa de revisão e é exatamente o que estoura.

**Fatiar por número de linhas em vez de por caracteres.** Mais intuitivo de ler na configuração.
Descartada porque o limite real é de tokens, e caractere aproxima token muito melhor que linha —
linha de diff varia de três caracteres a trezentos.

**Filtrar arquivo por tamanho do patch, descartando os maiores.** Baratearia o pior caso.
Descartada por descartar justamente onde há mais risco: um patch enorme é mais candidato a review, não
menos. O fatiamento resolve o custo sem jogar código fora.

## Consequências

**Positivas**

- O custo de LLM fica proporcional ao código que vale revisar, não ao tamanho do PR: lockfile e
  arquivo gerado saem antes de qualquer chamada.
- O agente sempre recebe um trecho com cabeçalho de hunk, então `file` e `line` do achado continuam
  apontando para o lugar certo — que é o que a fase 6 precisa para comentar inline.
- PR só de arquivo ignorado termina em review aprovada e barata, em vez de ficar pendurado até o
  timeout.
- Nenhuma mudança de contrato: `DiffReadyEvent` já tinha os quatro campos desde a fase 1.

**Negativas**

- **Contexto cortado entre chunks.** Um problema que só se vê olhando duas funções distantes do mesmo
  arquivo pode passar, porque cada agente vê um chunk por vez. É a troca aceita por caber na janela
  de contexto, e é falso-negativo, não falso-positivo.
- **O `diff-fetcher` passa a conhecer a estrutura do diff unificado.** O reconhecimento de `@@` no
  `DiffChunker` é acoplamento a um formato de terceiro. Fica coberto por teste de unidade, mas é
  código que só existe porque o formato é o que é.
- **`diff.max-chunk-chars` vira número a calibrar.** 12.000 é estimativa, não medida. A fase 7, com
  evals e métrica de custo, é que vai dizer se o valor está bom — e mexer nele muda o resultado das
  reviews, então é mudança para passar por eval, como manda a skill `run-evals`.
- **Arquivo renomeado sem mudança de conteúdo vem sem `patch`** e é ignorado em silêncio. É o
  comportamento desejado, mas significa que "arquivo que apareceu no PR" e "arquivo revisado" não são
  a mesma lista — quem for depurar uma review precisa saber disso.
- O número de chamadas à API cresce com a paginação: PR com 250 arquivos são três requisições antes
  de qualquer análise.
