package dev.bandeira.prsentinel.gateway.domain;

import java.util.Locale;

/**
 * Ação do evento {@code pull_request} do GitHub.
 *
 * <p>Quais ações merecem uma review é regra de negócio, não detalhe de HTTP — por isso vive no
 * domain e não num {@code Set<String>} dentro do controller.
 *
 * <p>Ação desconhecida vira {@link #OTHER} em vez de lançar: o GitHub adiciona ações novas ao
 * payload com o tempo, e uma ação que não conhecemos é simplesmente uma que não dispara review.
 */
public enum PullRequestAction {
  OPENED(true),
  SYNCHRONIZE(true),
  REOPENED(true),
  OTHER(false);

  private final boolean triggersReview;

  PullRequestAction(boolean triggersReview) {
    this.triggersReview = triggersReview;
  }

  public static PullRequestAction from(String raw) {
    if (raw == null || raw.isBlank()) {
      return OTHER;
    }
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "opened" -> OPENED;
      case "synchronize" -> SYNCHRONIZE;
      case "reopened" -> REOPENED;
      default -> OTHER;
    };
  }

  public boolean triggersReview() {
    return triggersReview;
  }
}
