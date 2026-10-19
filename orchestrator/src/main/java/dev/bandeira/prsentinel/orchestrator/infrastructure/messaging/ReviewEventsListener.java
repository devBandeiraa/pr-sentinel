package dev.bandeira.prsentinel.orchestrator.infrastructure.messaging;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.orchestrator.application.AgentResultOutcome;
import dev.bandeira.prsentinel.orchestrator.application.RecordAgentResultUseCase;
import dev.bandeira.prsentinel.orchestrator.application.RegisterReviewUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * As duas entradas do orchestrator: o pedido de review e o resultado de cada agente.
 *
 * <p>Filas separadas de propósito (ADR-001): um acúmulo de resultados de agente não pode atrasar o
 * registro de reviews novas, que é a operação que precisa acontecer antes de tudo.
 *
 * <p>{@code ReviewNotRegisteredException} sobe de propósito, para o retry da fila agir — é corrida
 * entre as duas filas, e esperar um pouco resolve.
 */
@Component
public class ReviewEventsListener {

  private static final Logger log = LoggerFactory.getLogger(ReviewEventsListener.class);

  public static final String REVIEWS_SERVICE = "orchestrator-reviews";
  public static final String RESULTS_SERVICE = "orchestrator-results";

  private final RegisterReviewUseCase registerReview;
  private final RecordAgentResultUseCase recordAgentResult;

  public ReviewEventsListener(
      RegisterReviewUseCase registerReview, RecordAgentResultUseCase recordAgentResult) {
    this.registerReview = registerReview;
    this.recordAgentResult = recordAgentResult;
  }

  @RabbitListener(queues = "pr-sentinel." + REVIEWS_SERVICE)
  public void onReviewRequested(ReviewRequestedEvent event) {
    if (registerReview.handle(event)) {
      log.info(
          "Review registrada review={} pr={}", event.reviewId(), event.pullRequest().fullName());
    } else {
      log.info("Review já registrada, entrega repetida review={}", event.reviewId());
    }
  }

  @RabbitListener(queues = "pr-sentinel." + RESULTS_SERVICE)
  public void onAgentCompleted(AgentCompletedEvent event) {
    AgentResultOutcome outcome = recordAgentResult.handle(event);

    switch (outcome) {
      case WAITING_FOR_AGENTS ->
          log.info(
              "Agente {} concluiu review={} status={}; aguardando os demais",
              event.agent(),
              event.reviewId(),
              event.status());
      case PUBLISHED ->
          log.info(
              "Review consolidada e publicada review={} pelo agente {}",
              event.reviewId(),
              event.agent());
      case DUPLICATE ->
          log.info(
              "Resultado repetido do agente {} ignorado review={}",
              event.agent(),
              event.reviewId());
      case ALREADY_CLOSED ->
          log.warn(
              "Agente {} respondeu depois do fechamento review={}; resultado gravado sem"
                  + " republicar",
              event.agent(),
              event.reviewId());
    }
  }
}
