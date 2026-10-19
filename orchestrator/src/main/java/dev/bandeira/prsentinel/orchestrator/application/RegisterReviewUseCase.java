package dev.bandeira.prsentinel.orchestrator.application;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;

/**
 * Registra uma review nova, em {@code PENDING}.
 *
 * <p>Idempotente por construção: o {@code deliveryId} é único na tabela {@code review} desde a fase
 * 1, e a reentrega da mesma mensagem pela fila não cria uma segunda linha. É a mesma garantia que o
 * gateway já aplica na borda (ADR-005), repetida aqui porque a fila entrega ao menos uma vez.
 */
public class RegisterReviewUseCase {

  private final Reviews reviews;

  public RegisterReviewUseCase(Reviews reviews) {
    this.reviews = reviews;
  }

  /**
   * @return {@code true} se a review foi registrada agora; {@code false} se já existia
   */
  public boolean handle(ReviewRequestedEvent event) {
    return reviews.register(event);
  }
}
