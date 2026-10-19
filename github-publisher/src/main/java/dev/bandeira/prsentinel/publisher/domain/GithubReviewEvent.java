package dev.bandeira.prsentinel.publisher.domain;

/**
 * O {@code event} aceito pela API de review do GitHub, menos o {@code APPROVE}.
 *
 * <p>Aprovar está fora de propósito (ADR-009): um bot aprovando dá a impressão de revisão humana
 * feita, e o pior caso de {@code COMMENT} — ninguém leu — é melhor que o pior caso de {@code
 * APPROVE}, que é todos pensarem que alguém leu.
 */
public enum GithubReviewEvent {
  COMMENT,
  REQUEST_CHANGES
}
