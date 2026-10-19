package dev.bandeira.prsentinel.publisher.domain;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;

/**
 * Decide se a review vai como {@code COMMENT} ou {@code REQUEST_CHANGES} (ADR-009).
 *
 * <p>O padrão é nunca bloquear merge. Achado de LLM passou por um limiar de confiança de 0.6, não
 * por uma pessoa: travar o merge do time com base nisso é o caminho mais curto para alguém desligar
 * o bot. Quem quiser o modo duro liga {@code publisher.request-changes}.
 */
public final class ReviewEventPolicy {

  private final boolean requestChangesEnabled;

  public ReviewEventPolicy(boolean requestChangesEnabled) {
    this.requestChangesEnabled = requestChangesEnabled;
  }

  public GithubReviewEvent eventFor(Verdict verdict) {
    boolean blocking = requestChangesEnabled && verdict == Verdict.CHANGES_REQUESTED;
    return blocking ? GithubReviewEvent.REQUEST_CHANGES : GithubReviewEvent.COMMENT;
  }
}
