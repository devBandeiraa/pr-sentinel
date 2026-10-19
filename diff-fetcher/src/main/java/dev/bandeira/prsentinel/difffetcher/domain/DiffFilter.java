package dev.bandeira.prsentinel.difffetcher.domain;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Decide quais arquivos do PR valem uma review.
 *
 * <p>Cada arquivo que passa daqui vira chunk, e cada chunk custa três chamadas de LLM pelo fan-out
 * da ADR-001. Este filtro é o que mantém o custo proporcional ao código que importa (ADR-007).
 *
 * <p>Usa o {@code PathMatcher} do JDK e não o {@code AntPathMatcher} do Spring: a decisão é regra
 * de negócio e mora no {@code domain}, onde framework não entra.
 */
public final class DiffFilter {

  private final List<PathMatcher> matchers;

  public DiffFilter(List<String> ignoredPatterns) {
    this.matchers = compile(ignoredPatterns == null ? List.of() : ignoredPatterns);
  }

  public boolean isReviewable(ChangedFile file) {
    if (!file.hasPatch()) {
      return false;
    }
    if (file.status() == FileStatus.REMOVED) {
      return false;
    }
    Path path = Path.of(file.path());
    return matchers.stream().noneMatch(matcher -> matcher.matches(path));
  }

  private static List<PathMatcher> compile(List<String> patterns) {
    List<PathMatcher> compiled = new ArrayList<>();
    for (String pattern : patterns) {
      if (pattern == null || pattern.isBlank()) {
        continue;
      }
      add(compiled, pattern);

      // No glob do JDK, "**/x" exige a barra: ele casa "web/x" e não casa "x" na raiz. Como os
      // padrões vêm escritos no estilo "**/package-lock.json", o arquivo na raiz do repositório
      // escaparia do filtro. Daí compilar também a versão sem o prefixo.
      if (pattern.startsWith("**/")) {
        add(compiled, pattern.substring(3));
      }
    }
    return List.copyOf(compiled);
  }

  private static void add(List<PathMatcher> compiled, String pattern) {
    try {
      compiled.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      // Padrão vem de configuração. Um glob malformado ignora a si mesmo; derrubar o filtro
      // inteiro faria um erro de digitação no application.yml parar a review de todos os PRs.
    }
  }
}
