# ADR-004 — Saída estruturada do LLM com limiar de confiança

- **Status:** Aceita
- **Data:** 2026-09-28

## Contexto

O resultado de um agente precisa virar comentário inline no PR, o que exige dados precisos: arquivo,
linha, severidade, explicação e sugestão. Texto livre do LLM não serve — seria preciso parsear prosa
para descobrir a que linha cada comentário se refere.

Há um problema mais sério que o formato: **falso positivo destrói a confiança na ferramenta**. Um
revisor automático que aponta problemas inexistentes é rapidamente ignorado, e aí mesmo os achados
corretos param de ser lidos. LLMs tendem a produzir achados plausíveis mas incorretos quando não
encontram nada de real — o incentivo é sempre "achar alguma coisa".

## Decisão

Duas medidas combinadas.

**1. Saída estruturada.** O agente pede ao LLM um objeto que corresponde a `AgentOutput`
(lista de `Finding`), obtido via `ChatClient.call().entity(AgentOutput.class)` do Spring AI, que
valida a resposta contra o schema. Resposta que não obedece ao schema não vira achado.

**2. Limiar de confiança.** Cada `Finding` carrega um campo `confidence` (0.0–1.0) que o próprio
modelo atribui. Achados com confiança **abaixo de `agent.min-confidence` (padrão 0.6) são
descartados** antes de sair do agente — não chegam ao orchestrator nem ao PR.

O prompt do agente é explícito sobre o que significa cada faixa de confiança e autoriza devolver
lista vazia. "Nenhum achado" é uma resposta válida e esperada.

O limiar é configurável por container, então um agente pode ser mais conservador que outro sem
mudança de código.

## Alternativas consideradas

**Texto livre com parsing por regex.** Frágil por construção: qualquer variação de formatação do
modelo quebra o parser, e o modelo varia. Também não dá para distinguir achado de comentário
introdutório.

**Função/tool calling em vez de structured output.** Resolveria o formato igualmente bem, mas
adiciona uma camada de indireção sem ganho aqui — não há efeito colateral a executar, só um valor a
extrair.

**Publicar todos os achados, sem limiar.** Maximiza recall. Descartado pelo motivo central desta
ADR: o custo de um falso positivo é muito maior que o de um falso negativo. Um problema não
apontado é o estado atual do mundo; um problema inventado desperdiça o tempo de quem revisa e
corrói a credibilidade da ferramenta.

**Filtrar por severidade em vez de confiança.** Mistura duas dimensões diferentes: um achado pode ser
crítico *se* for verdadeiro, mas incerto. Severidade responde "quão grave é"; confiança responde
"quão provável é que exista". O filtro tem que ser sobre a segunda.

**Um segundo LLM validando os achados do primeiro (LLM as judge).** Melhoraria a precisão, mas
dobraria custo e latência por chunk. Fica como possibilidade para a fase 7, apoiada nos dados dos
evals.

## Consequências

**Positivas**

- Achados chegam prontos para virar comentário inline, sem parsing.
- O limiar dá um botão direto para trocar precisão por recall, por agente, sem deploy de código.
- Com `Usage` (modelo, tokens, latência) registrado por agente, dá para medir custo e qualidade
  juntos.

**Negativas**

- `confidence` é auto-avaliação do modelo, não uma probabilidade calibrada. O valor de 0.6 é um
  ponto de partida que precisa ser validado pelos evals (skill `run-evals`), não uma constante
  confiável.
- Achados reais com confiança baixa são perdidos silenciosamente. A contagem de descartados deve
  virar métrica na fase 7, senão o filtro vira uma caixa-preta.
- Saída estruturada consome tokens extras com o schema e pode falhar em modelos menores.
- Se o JSON vier inválido, é preciso uma política de retry com o erro de validação (previsto para a
  fase 4); hoje o agente devolve lista vazia.
- Mudar o prompt muda o comportamento do `confidence`. Por isso toda alteração de prompt exige rodar
  os evals antes e depois, conforme a skill `run-evals`.
