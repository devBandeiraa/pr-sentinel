---
name: conventional-commit
description: Use sempre que for criar um commit no PR Sentinel. Define o formato da mensagem (tipo, escopo do módulo, assunto no imperativo até 72 caracteres, corpo explicando o porquê, BREAKING CHANGE) e as checagens obrigatórias antes de commitar.
---

# Commit no padrão Conventional Commits

## Formato

```
<tipo>(<escopo>): <assunto>

<corpo — por que a mudança existe>

<rodapé — BREAKING CHANGE, refs>
```

### Tipo

| Tipo | Quando |
|---|---|
| `feat` | Funcionalidade nova visível para quem usa |
| `fix` | Correção de bug |
| `refactor` | Muda estrutura sem mudar comportamento |
| `test` | Só testes |
| `docs` | Só documentação (README, ADR, CHANGELOG, CLAUDE.md) |
| `chore` | Manutenção sem efeito em produção (config, dependência, `.claude/`) |
| `ci` | Workflow do GitHub Actions |
| `build` | Build, POM, Dockerfile, plugin |

### Escopo

O módulo tocado: `common`, `webhook-gateway`, `diff-fetcher`, `review-agent`, `orchestrator`,
`github-publisher`.

Omita o escopo quando a mudança for do repositório inteiro (`chore: configura padrões de
versionamento`). Se o commit toca três módulos, provavelmente são três commits.

### Assunto

- **Imperativo**, como uma ordem: "adiciona", "corrige", "remove" — nunca "adicionado",
  "adicionando", "adiciona-se".
- **Até 72 caracteres**, contando tipo e escopo.
- Minúscula na primeira letra, **sem ponto final**.
- Em português.
- Diga o efeito, não o mecanismo: `adiciona idempotência por delivery id` e não
  `adiciona if no controller`.

### Corpo

Opcional em commit trivial, **obrigatório** quando a mudança não é óbvia.

Explique **por que**, não o que — o "o que" está no diff. Bom corpo responde: que problema existia,
por que essa solução, o que foi considerado e descartado.

Quebre em 72 colunas. Referencie ADR ou issue quando houver.

### Rodapé

Mudança incompatível:

```
BREAKING CHANGE: pr.diff.ready agora exige o campo language em cada chunk.
Consumidores antigos precisam migrar para pr.diff.ready.v2.
```

Em evento de `common`, leia antes a skill `event-contract` — quase sempre dá para evitar a quebra.

## Exemplos

```
feat(webhook-gateway): adiciona idempotência por delivery id
```

```
fix(orchestrator): evita review duplicada quando agente responde após timeout

O resultado tardio passava pela checagem de completude e publicava um
segundo pr.review.ready, gerando dois comentários no mesmo PR.

Agora o resultado é gravado, mas só publica quem transiciona a review
de IN_PROGRESS para COMPLETED. A unicidade review_id + agent já
impedia a duplicação em banco; faltava a guarda na publicação.

Ref: ADR-003
```

```
build: adiciona Spotless, JaCoCo e ArchUnit
```

## Antes de commitar — checagens obrigatórias

**1. Formatação**

```bash
./mvnw spotless:check     # se falhar: ./mvnw spotless:apply
```

**2. Testes**

```bash
./mvnw verify
```

O commit tem que compilar e passar. Commit que quebra o build não é atômico — ele torna o `git
bisect` inútil.

**3. Nenhum segredo no diff**

```bash
git status
git diff --cached
git diff --cached -U0 | grep -nEi '(api[_-]?key|secret|token|password|private[_-]?key|ghp_|ghs_|sk-ant|BEGIN [A-Z ]*PRIVATE KEY)'
```

Confirme que não entrou `.env`, `*.pem`, nada de `secrets/` nem de `target/`. Nome de variável de
ambiente pode; valor não.

**4. Um commit = uma mudança lógica**

Se o assunto precisa de "e" para descrever o commit, são dois commits. Use `git add -p` para
separar.

## Checklist

- [ ] Tipo correto e escopo do módulo
- [ ] Assunto no imperativo, até 72 caracteres, sem ponto final
- [ ] Corpo explica o porquê (se a mudança não for trivial)
- [ ] `BREAKING CHANGE` no rodapé, se houver
- [ ] `./mvnw verify` passou
- [ ] Nenhum segredo, `.env`, `*.pem` ou `target/` no diff
- [ ] Uma mudança lógica só
