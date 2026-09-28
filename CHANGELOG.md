# Changelog

Todas as mudanças notáveis deste projeto são documentadas neste arquivo.

O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/),
e o versionamento segue [SemVer](https://semver.org/lang/pt-BR/).

## [Unreleased]

### Fixed

- `mvnw` e os hooks em `.claude/hooks/` estavam versionados sem bit de execução, o que fazia o CI
  falhar com `Permission denied` no Linux.

## [0.1.0] - 2026-09-28

### Added

- Estrutura de módulos Maven multi-módulo (`common`, `webhook-gateway`, `diff-fetcher`, `review-agent`, `orchestrator`, `github-publisher`).
- Contratos de eventos (`ReviewRequestedEvent`, `DiffReadyEvent`, `AgentCompletedEvent`, `ReviewReadyEvent`) e topologia RabbitMQ em `common`.
- `SignatureVerifier` com comparação HMAC em tempo constante no `webhook-gateway`.
- Modelo de dados inicial do `orchestrator` (Flyway `V1__create_review_tables.sql`).
- Padrões de código: Spotless (google-java-format), JaCoCo (mínimo 80% em `domain`/`application`), ArchUnit para isolar a camada `domain`.
- Pacotes `domain`, `application` e `infrastructure` em cada serviço, com as regras de camada validadas por `ArchitectureTest`.
- CI roda `spotless:check`, testes, ArchUnit e o gate de cobertura.
- Documentação viva: `CLAUDE.md` e ADRs 001–004 em `docs/adr/`.
- Subagentes e skills do Claude Code para o fluxo de desenvolvimento das próximas fases.
- Templates de PR, issues e configuração do Dependabot.

### Fixed

- `review-agent` não compilava: faltava a dependência `spring-boot-starter-validation` usada por `AgentProperties`.
