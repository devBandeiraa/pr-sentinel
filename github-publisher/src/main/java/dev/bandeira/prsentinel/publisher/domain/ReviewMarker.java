package dev.bandeira.prsentinel.publisher.domain;

import java.util.UUID;

/**
 * Marcador invisível que o PR Sentinel deixa no corpo da review (ADR-009).
 *
 * <p>É comentário HTML, então não aparece renderizado no PR, e é o que torna a publicação
 * idempotente sem banco: antes de postar, o serviço procura o marcador desta review entre as que já
 * existem no PR. Serve também para depurar — dá para descobrir qual {@code reviewId} gerou aquele
 * comentário.
 */
public final class ReviewMarker {

  private static final String PREFIX = "<!-- pr-sentinel:review-id=";
  private static final String SUFFIX = " -->";

  private ReviewMarker() {}

  public static String of(UUID reviewId) {
    return PREFIX + reviewId + SUFFIX;
  }

  /** Se o corpo de uma review existente no PR é desta review. */
  public static boolean isPresentIn(String body, UUID reviewId) {
    return body != null && body.contains(of(reviewId));
  }
}
