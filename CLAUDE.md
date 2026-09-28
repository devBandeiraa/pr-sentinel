# PR Sentinel — guia para o Claude Code

## Visão

Revisor de código multi-agente para Pull Requests do GitHub. Um PR aberto ou atualizado dispara um
webhook; o diff é buscado e dividido em chunks; três agentes de IA (segurança, performance, padrões)
analisam em paralelo; o orchestrator consolida e o resultado vira uma review publicada no PR.

Arquitetura orientada a eventos sobre um topic exchange do RabbitMQ (`pr-sentinel.events`). Cada
consumidor tem fila própria com DLQ. Detalhes e diagrama no [README](README.md).

## Mapa dos módulos

| Módulo | Responsabilidade | Consome | Publica |
|---|---|---|---|
| `common` | Contratos de eventos (records), topologia de mensageria, regras ArchUnit (test-jar) | — | — |
| `webhook-gateway` | Valida HMAC do GitHub, filtra ações, publica o início da review | HTTP `/webhooks/github` | `pr.review.requested` |
| `diff-fetcher` | Busca o diff na API do GitHub, filtra e divide em chunks | `pr.review.requested` | `pr.diff.ready` |
| `review-agent` | Analisa chunks com o LLM; o tipo vem de `AGENT_TYPE` | `pr.diff.ready` | `pr.agent.completed` |
| `orchestrator` | Estado da review, consolidação, deduplicação, timeout | `pr.review.requested`, `pr.agent.completed` | `pr.review.ready` |
| `github-publisher` | Publica a review no PR respeitando rate limit | `pr.review.ready` | — |

`review-agent` é **um módulo rodando como três containers**, cada um com `AGENT_TYPE` diferente
(ver [ADR-002](docs/adr/002-review-agent-como-tres-containers.md)).

## Comandos

```bash
./mvnw verify                      # build completo: Spotless, testes, ArchUnit, cobertura
./mvnw spotless:apply              # corrige a formatação
./mvnw -pl <módulo> -am verify     # só um módulo e suas dependências
./mvnw -pl <módulo> test -Dtest=X  # um teste específico

cp .env.example .env               # preencha as variáveis antes de subir
docker compose up --build          # ambiente completo (RabbitMQ, Postgres, serviços)
```

Webhook local em `http://localhost:8080/webhooks/github`; painel do RabbitMQ em
`http://localhost:15672`.

## Convenções de código

**Camadas, em cada serviço** — validadas por `ArchitectureTest`, que aplica as regras de
`LayerRules` (publicado pelo `common` como test-jar):

- `domain` — regra de negócio pura. Sem Spring, sem JPA, sem Jackson, sem I/O.
- `application` — casos de uso. Orquestra o `domain` através de portas declaradas aqui; não conhece
  `infrastructure`.
- `infrastructure` — adaptadores: HTTP, RabbitMQ, JPA, GitHub, LLM. Única camada que conhece
  framework.

**Estilo**

- Injeção por construtor. Nunca `@Autowired` em campo.
- `record` para DTOs e eventos.
- Sem Lombok.
- Formatação é do Spotless (google-java-format); não discuta estilo em review, rode
  `./mvnw spotless:apply`.
- Cobertura mínima de 80% (linha) em `domain` e `application`; o build falha abaixo disso.
  `infrastructure` é coberta por testes de integração, que não entram nessa contagem.

**Idioma** — nomes de código (classes, métodos, variáveis, pacotes) em **inglês**. Logs, mensagens
de commit, documentação, ADRs, comentários e mensagens de review em **português**.

**Segredos** — só por variável de ambiente. Nunca em código, teste, fixture ou log. Chaves de arquivo
ficam em `secrets/`, que é ignorado pelo git. Nunca logue token, assinatura, chave ou corpo cru de
webhook.

## Convenções de git

- **Conventional Commits** com escopo do módulo: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`,
  `ci`, `build`. Ex.: `feat(webhook-gateway): adiciona idempotência por delivery id`.
- Commits pequenos e atômicos: um commit = uma mudança lógica que compila e passa nos testes.
- Branches: `feat/<fase>-<descrição>`, `fix/<descrição>`, `chore/<descrição>`. Nunca commitar direto
  na `main`.
- Um PR por fase, merge via squash, usando o template de PR.
- **SemVer** com tags anotadas: cada fase concluída vira uma minor (`v0.2.0` para a fase 2, e assim
  por diante). `v1.0.0` quando o fluxo rodar ponta a ponta.
- `CHANGELOG.md` no formato Keep a Changelog, atualizado em `[Unreleased]` a cada PR.

## Fluxo de trabalho

**Todo trabalho — fase ou feature — segue a skill [`feature-workflow`](.claude/skills/feature-workflow/SKILL.md).**
Não pule etapas: o plano do `architect` precisa da minha aprovação antes de qualquer código, e os
testes vêm antes da implementação.

Skills disponíveis: `feature-workflow`, `conventional-commit`, `event-contract`, `add-review-agent`,
`write-adr`, `run-evals`, `release`.

Subagentes: `architect`, `test-engineer`, `code-reviewer`, `security-auditor`, `prompt-engineer`,
`docs-writer`.

## Decisões registradas

As decisões estruturais estão em [`docs/adr/`](docs/adr/). Leia-as antes de propor mudanças de
arquitetura; uma decisão nova exige uma ADR (ver skill `write-adr`).

## Roadmap

O estado das fases está no [README](README.md#roadmap). A fase 1 (fundação) está concluída.
