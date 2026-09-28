---
name: write-adr
description: Use ao registrar uma decisão de arquitetura do PR Sentinel em docs/adr/, ou quando o architect indicar que uma fase precisa de ADR nova. Define numeração, nome do arquivo, template e o que fazer quando uma decisão substitui outra.
---

# Escrever uma ADR

Uma ADR (Architecture Decision Record) registra **por que** uma decisão foi tomada, com o contexto
da época. Daqui a seis meses, quando alguém perguntar "por que isso é assim?", a ADR responde sem
depender da memória de ninguém.

## Quando escrever

**Escreva** quando a decisão:
- é estrutural — muda como os módulos se relacionam ou como os dados fluem;
- é cara de reverter;
- tem alternativa razoável que foi descartada (e alguém vai propor essa alternativa de novo);
- contraria ou estende uma ADR existente;
- escolhe uma tecnologia ou dependência relevante.

**Não escreva** para decisão de implementação reversível numa tarde, escolha de nome, ou detalhe que
o código já deixa óbvio. ADR demais tem o mesmo efeito de ADR nenhuma: ninguém lê.

Na dúvida, pergunte ao usuário.

## Numeração e arquivo

Próximo número livre, com três dígitos, sem reaproveitar número de ADR descartada:

```bash
ls docs/adr/
```

Nome: `docs/adr/<NNN>-<titulo-em-kebab-case>.md`, título em português, descrevendo a **decisão**, não
o problema.

- Bom: `005-idempotencia-por-delivery-id.md`
- Ruim: `005-problema-de-duplicacao.md`

Depois de criar, adicione a linha na tabela de `docs/adr/README.md`.

## Template

```markdown
# ADR-<NNN> — <Título: a decisão, em uma linha>

- **Status:** Aceita
- **Data:** <AAAA-MM-DD>

## Contexto

Qual a situação e qual a força que obriga a decidir. Fatos, restrições e requisitos — ainda sem
solução. Quem ler isto daqui a um ano precisa entender o problema sem conhecer o código de hoje.

Diga o que era verdade na época: volume esperado, limitações do ambiente, prazos, o que já existia.

## Decisão

O que foi decidido, na voz ativa e no presente: "O orchestrator aplica um timeout por review."

Seja concreto: nomes de classe, routing keys, propriedades de configuração, valores padrão.

## Alternativas consideradas

Uma subseção por alternativa séria. Para cada uma: o que era, e **por que foi descartada**.

Esta é a seção mais valiosa da ADR. Sem ela, a decisão parece arbitrária e alguém vai reabrir a
discussão do zero. Alternativa listada só para encher não ajuda — inclua as que foram de fato
consideradas.

## Consequências

**Positivas** — o que fica melhor.

**Negativas** — o que fica pior, o que passa a ser obrigatório, que dívida foi assumida. Uma ADR só
com consequências positivas não é honesta; toda decisão de arquitetura troca alguma coisa por outra.

Inclua o que a decisão **obriga** daqui em diante (ex.: "consumidores precisam ser idempotentes").
```

## Status

| Status | Significado |
|---|---|
| `Proposta` | Em discussão, ainda não vale |
| `Aceita` | Em vigor |
| `Substituída por ADR-00X` | Não vale mais; a nova explica o porquê |
| `Descartada` | Foi proposta e recusada — mantida para registro |

## Quando uma decisão muda

**Nunca reescreva uma ADR aceita.** Ela é o registro do que se sabia naquele momento; reescrevê-la
apaga exatamente a informação que ela existe para guardar.

Em vez disso:

1. Crie uma ADR nova com o número seguinte.
2. Na nova, no contexto, explique o que mudou desde a anterior e por que a decisão não se sustenta
   mais.
3. Marque a nova como `Aceita` e cite: "Substitui a ADR-00X".
4. Edite **apenas o campo Status** da antiga para `Substituída por ADR-00Y`.
5. Atualize a tabela em `docs/adr/README.md`.

Correção de erro de digitação ou de link quebrado pode ser feita direto.

## Estilo

- Português, frases curtas, voz ativa.
- Presente do indicativo na decisão; passado no contexto.
- Sem jargão de marketing e sem hedge ("talvez", "possivelmente") na seção Decisão — decisão é
  afirmativa.
- Linke outras ADRs pelo caminho relativo.
- Nomes de código entre crases.

## Checklist

- [ ] Número é o próximo livre; arquivo no padrão `<NNN>-<kebab-case>.md`
- [ ] Título descreve a decisão, não o problema
- [ ] Contexto dá para entender sem conhecer o código
- [ ] Decisão é concreta, com nomes e valores
- [ ] Pelo menos duas alternativas reais, com o motivo do descarte
- [ ] Consequências negativas listadas de verdade
- [ ] Linha adicionada em `docs/adr/README.md`
- [ ] ADR substituída teve só o Status alterado
- [ ] Entrada no `CHANGELOG.md`
