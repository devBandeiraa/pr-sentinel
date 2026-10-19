package dev.bandeira.prsentinel.orchestrator.application;

import java.util.UUID;

/**
 * Chegou resultado de agente para uma review que o orchestrator ainda não registrou.
 *
 * <p>É corrida, não erro de lógica: {@code pr.review.requested} vai para duas filas, e nada garante
 * que a deste serviço seja consumida antes da do diff-fetcher. A exceção propaga para o listener
 * rejeitar a mensagem, e o retry da fila costuma resolver — quando não resolve, a DLQ guarda o
 * resultado para análise em vez de descartá-lo em silêncio.
 */
public class ReviewNotRegisteredException extends RuntimeException {

  public ReviewNotRegisteredException(UUID reviewId) {
    super("Review não registrada: " + reviewId);
  }
}
