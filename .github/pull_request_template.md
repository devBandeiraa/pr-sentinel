## O quê

<!-- O que este PR muda, em uma ou duas frases. -->

## Por quê

<!-- Qual problema isso resolve ou qual decisão motivou a mudança. Linke a ADR ou issue relevante. -->

## Como testar

<!-- Passos para validar manualmente, se aplicável. -->

```bash
./mvnw verify
```

## Checklist

- [ ] `./mvnw verify` passa localmente (Spotless, testes, JaCoCo, ArchUnit)
- [ ] Testes cobrem o comportamento novo/alterado
- [ ] Nenhum segredo ou dado sensível no diff (código, testes, logs)
- [ ] `CHANGELOG.md` atualizado em `[Unreleased]`
- [ ] Documentação relevante atualizada (README, ADR, comentários de prompt)
- [ ] Mudança em evento de `common` é compatível ou usa routing key versionada (ver skill `event-contract`)
