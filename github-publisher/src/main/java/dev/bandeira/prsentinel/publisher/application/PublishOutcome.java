package dev.bandeira.prsentinel.publisher.application;

/** Como terminou a publicação de uma review. O adaptador traduz isto em log. */
public enum PublishOutcome {
  /** Review postada com os comentários posicionados. */
  PUBLISHED,
  /**
   * Review postada só com o resumo, porque o GitHub recusou os comentários inline. Os achados vão
   * listados no corpo (ADR-009).
   */
  PUBLISHED_WITHOUT_INLINE,
  /** O PR já tinha a review desta análise; nada foi postado. */
  ALREADY_PUBLISHED
}
