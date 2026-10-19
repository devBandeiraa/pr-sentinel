package dev.bandeira.prsentinel.difffetcher.domain;

import java.util.Locale;

/**
 * Como o arquivo mudou no PR, segundo o campo {@code status} da API do GitHub.
 *
 * <p>Status desconhecido vira {@link #OTHER} em vez de lançar, pelo mesmo motivo que {@code
 * PullRequestAction} no gateway: o GitHub acrescenta valores com o tempo, e um valor que não
 * conhecemos não pode derrubar a review de um PR inteiro.
 */
public enum FileStatus {
  ADDED,
  MODIFIED,
  REMOVED,
  RENAMED,
  COPIED,
  CHANGED,
  UNCHANGED,
  OTHER;

  public static FileStatus from(String raw) {
    if (raw == null || raw.isBlank()) {
      return OTHER;
    }
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "added" -> ADDED;
      case "modified" -> MODIFIED;
      case "removed" -> REMOVED;
      case "renamed" -> RENAMED;
      case "copied" -> COPIED;
      case "changed" -> CHANGED;
      case "unchanged" -> UNCHANGED;
      default -> OTHER;
    };
  }
}
