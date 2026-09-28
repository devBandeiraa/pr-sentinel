---
name: release
description: Use ao concluir uma fase do roadmap do PR Sentinel, depois do PR da fase estar na main. Move o [Unreleased] do CHANGELOG para a versão nova, atualiza a versão nos POMs, cria a tag anotada e marca a fase no roadmap do README.
---

# Fechar uma fase

Cada fase concluída vira uma **minor**: fase 2 → `v0.2.0`, fase 3 → `v0.3.0`, e assim por diante.
`v1.0.0` sai quando o fluxo rodar ponta a ponta num repositório real (fim da fase 6, ou quando o
usuário decidir).

## Antes de começar

A fase só fecha quando **tudo** dela está na `main`:

```bash
git checkout main && git pull
git status          # tem que estar limpo
./mvnw verify       # tem que passar
```

Se houver PR da fase em aberto, **pare** — a fase não terminou.

Confirme com o usuário qual versão está sendo fechada antes de criar a tag. Tag é difícil de
desfazer depois de publicada.

## 1. Branch

```bash
git checkout -b chore/release-v0.2.0
```

## 2. CHANGELOG

Em `CHANGELOG.md`, transforme a seção `[Unreleased]` na versão nova e abra uma `[Unreleased]` vazia
acima:

```markdown
## [Unreleased]

## [0.2.0] - 2026-10-15

### Added
- ...
```

Antes de mover, **revise o conteúdo**: entradas escritas ao longo da fase costumam estar em
linguagem de commit. Reescreva para quem usa o projeto. Junte duplicatas, remova o que não tem
efeito visível.

A data é a de hoje, em `AAAA-MM-DD`.

## 3. Versão nos POMs

Todos os módulos herdam a versão do pai — use o plugin, não edite à mão:

```bash
./mvnw versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
```

Confira que os seis módulos e o pai ficaram consistentes:

```bash
grep -rn "<version>" --include=pom.xml . | grep -v "</parent>" | head -20
git diff
```

Decida com o usuário se a versão fica fixa (`0.2.0`) ou volta para `-SNAPSHOT` depois da tag. O
padrão do projeto é manter a versão fixa na tag e seguir em `0.2.0-SNAPSHOT` no commit seguinte.

## 4. Roadmap no README

Marque a fase concluída:

```markdown
- [x] Fase 2 — webhook-gateway com idempotência
```

Se a fase entregou algo que muda a descrição do projeto, ajuste o texto também — mas isso já deveria
ter vindo do `docs-writer` durante a fase.

## 5. Commit e merge

```bash
./mvnw verify
git add -A
git commit -m "chore: prepara release v0.2.0"
```

Abra o PR, faça o merge por squash e volte para a `main` atualizada.

## 6. Tag anotada — na `main`, depois do merge

**Sempre anotada** (`-a`), nunca leve. Tag leve não guarda autor, data nem mensagem.

```bash
git checkout main && git pull
git tag -a v0.2.0 -m "Fase 2 — webhook-gateway com idempotência

- Idempotência por delivery id do GitHub
- ...
"
git push origin v0.2.0
```

O resumo da mensagem sai do CHANGELOG da versão.

## 7. Conferir

```bash
git tag -n9 v0.2.0
git log --oneline -3
```

Se o repositório tiver remote no GitHub, crie o release a partir da tag, reaproveitando o texto do
CHANGELOG:

```bash
gh release create v0.2.0 --title "v0.2.0 — Fase 2" --notes-file <(sed -n '/## \[0.2.0\]/,/## \[0.1.0\]/p' CHANGELOG.md)
```

Confirme com o usuário antes — publicar release é visível para todo mundo.

## Erros a evitar

- **Tag antes do merge.** A tag aponta para um commit que não está na `main`.
- **Tag leve.** Use `-a`.
- **Mover `[Unreleased]` sem revisar.** O CHANGELOG é lido por quem não acompanhou a fase.
- **Pular a minor.** Fase 3 é `v0.3.0` mesmo que a fase 2 tenha sido pequena.
- **`v1.0.0` antecipado.** Só quando o fluxo funcionar ponta a ponta de verdade.
- **Apagar ou mover tag publicada.** Não faça; crie uma versão nova.

## Checklist

- [ ] Toda a fase está na `main`; nenhum PR em aberto
- [ ] `./mvnw verify` verde
- [ ] `[Unreleased]` revisado e movido para a versão, com data
- [ ] `[Unreleased]` vazio recriado
- [ ] Versão atualizada em todos os POMs via `versions:set`
- [ ] Fase marcada no roadmap do README
- [ ] Commit `chore: prepara release vX.Y.0` mergeado
- [ ] Tag **anotada** criada na `main` depois do merge
- [ ] Tag conferida com `git tag -n9`
