---
name: feature-workflow
description: O fluxo padrão de toda fase ou feature do PR Sentinel. Use sempre que for começar a fase 2, 3, 4, 5, 6 ou 7, ou qualquer feature/correção não trivial. Cobre branch, plano do architect, TDD, revisão, verify, documentação, commit e PR.
---

# Fluxo de uma fase ou feature

Siga os passos na ordem. Não pule etapa. Dois pontos têm parada obrigatória: **o plano precisa da
aprovação do usuário** (passo 2) e **os testes vêm antes da implementação** (passo 3).

## 1. Criar a branch

Nunca trabalhe na `main`.

```bash
git checkout main && git pull
git checkout -b feat/fase-2-idempotencia-webhook
```

Padrão: `feat/<fase>-<descrição>`, `fix/<descrição>` ou `chore/<descrição>`. Descrição em
kebab-case, em português, curta.

Confira que a `main` está limpa antes (`git status`).

## 2. Plano do `architect` — **exige aprovação**

Acione o subagente `architect` com o objetivo da fase. Ele lê código e ADRs e devolve arquivos
afetados, contratos de eventos, modelo de dados, riscos e se precisa de ADR nova.

**Apresente o plano ao usuário e espere aprovação explícita.** Não escreva código, nem teste, antes
disso. Se ele levantar pontos de decisão, resolva-os com o usuário agora — não no meio da
implementação.

Se o plano indicar ADR nova, escreva a ADR com a skill `write-adr` e inclua-a na aprovação.

Se a fase mexe em evento de `common`, leia a skill `event-contract` antes de seguir.

## 3. Testes que falham (`test-engineer`)

Acione o subagente `test-engineer` com o plano aprovado.

Ele escreve os testes **antes** da implementação. Confirme que eles rodam e **falham pelo motivo
certo** (funcionalidade ausente), não por erro de compilação acidental:

```bash
./mvnw -pl <módulo> -am test
```

Se algum teste passar já nesta etapa, ele não está testando o que deveria. Volte.

## 4. Implementar

Escreva o mínimo para fazer os testes passarem, seguindo o plano e as convenções do `CLAUDE.md`:

- regra de negócio em `domain` (sem Spring), caso de uso em `application`, adaptador em
  `infrastructure`;
- injeção por construtor, `record` para DTO e evento, sem Lombok;
- nomes em inglês, log em português;
- segredo só por variável de ambiente.

Rode os testes do módulo enquanto trabalha. Não adicione funcionalidade além do que o plano previu.

## 5. Revisão: `code-reviewer` + `security-auditor` **em paralelo**

Acione os dois subagentes na **mesma mensagem**, para rodarem juntos. Ambos são somente leitura e
revisam `git diff main...HEAD`.

Corrija **todos** os achados **CRITICAL** e **HIGH** antes de seguir. Para MEDIUM e LOW: corrija se
for barato, ou registre o motivo de não corrigir na descrição do PR.

Depois de corrigir, rode os testes de novo.

## 6. `./mvnw verify`

```bash
./mvnw verify
```

Tem que passar inteiro: Spotless, testes, ArchUnit e o gate de 80% do JaCoCo em `domain` e
`application`. Se o Spotless reclamar, `./mvnw spotless:apply`.

**Não siga com o build vermelho.** Se a cobertura não bateu, faltam testes — não baixe o limiar.

## 7. Documentação (`docs-writer`)

Acione o subagente `docs-writer`. Ele atualiza `CHANGELOG.md` (seção `[Unreleased]`), o `README.md`
(roadmap, tabela de módulos, diagrama) e as ADRs conforme o que mudou.

Se a fase mexeu em prompt, os evals já devem ter sido rodados antes e depois pelo `prompt-engineer`
(skill `run-evals`) — anexe os números à descrição do PR.

## 8. Commit

Use a skill `conventional-commit`. Commits pequenos e atômicos: cada um compila e passa nos testes.

Antes de commitar, confira o que está sendo incluído:

```bash
git status
git diff --cached
```

Nenhum segredo, nenhum `.env`, nenhum `*.pem`, nenhum arquivo de `target/`.

## 9. Abrir o PR

```bash
git push -u origin <branch>
gh pr create --fill
```

O corpo segue `.github/pull_request_template.md`: o quê, por quê, como testar, checklist. Linke a
ADR e a issue. Marque o checklist de verdade — item marcado sem ter sido feito é pior que item
desmarcado.

Merge via **squash**.

## 10. Fechar a fase

Quando a fase inteira estiver na `main`, use a skill `release` para mover o `[Unreleased]` do
CHANGELOG para a versão nova, atualizar os POMs, criar a tag anotada (`v0.2.0` para a fase 2) e
marcar a fase no roadmap.

## Checklist

- [ ] Branch no padrão, criada a partir da `main` atualizada
- [ ] Plano do `architect` aprovado pelo usuário
- [ ] ADR escrita, se o plano pediu
- [ ] Testes escritos antes e falhando pelo motivo certo
- [ ] Implementação faz os testes passarem, sem escopo extra
- [ ] `code-reviewer` e `security-auditor` rodados em paralelo
- [ ] Achados CRITICAL e HIGH corrigidos
- [ ] `./mvnw verify` verde
- [ ] `docs-writer` rodado; CHANGELOG e README em dia
- [ ] Commits no padrão Conventional Commits, sem segredo no diff
- [ ] PR aberto com o template preenchido
