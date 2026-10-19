package dev.bandeira.prsentinel.orchestrator.application;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.orchestrator.application.port.ReviewPublisher;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;
import dev.bandeira.prsentinel.orchestrator.domain.FindingConsolidator;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewProgress;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import dev.bandeira.prsentinel.orchestrator.domain.VerdictPolicy;
import java.time.Clock;

/**
 * Grava o resultado de um agente e, quando o último responde, fecha a review.
 *
 * <p>Os três agentes chegam por mensagens independentes e em qualquer ordem. Quem responde por
 * último fecha a review — e "por último" não é decidido aqui, é consequência de a contagem ficar
 * completa depois da gravação.
 *
 * <p>Resultado que chega depois do timeout é gravado mas não republica: a transição condicional em
 * {@link Reviews#close} recusa, porque a review já está encerrada. É o caso que a ADR-003 previu.
 */
public class RecordAgentResultUseCase {

  private final Reviews reviews;
  private final ConsolidateReview consolidateReview;

  public RecordAgentResultUseCase(
      Reviews reviews,
      ReviewPublisher publisher,
      FindingConsolidator consolidator,
      VerdictPolicy verdictPolicy,
      Clock clock) {
    this.reviews = reviews;
    this.consolidateReview =
        new ConsolidateReview(reviews, publisher, consolidator, verdictPolicy, clock);
  }

  public AgentResultOutcome handle(AgentCompletedEvent event) {
    if (!reviews.recordAgentResult(event)) {
      return AgentResultOutcome.DUPLICATE;
    }

    ReviewSnapshot snapshot =
        reviews
            .snapshot(event.reviewId())
            .orElseThrow(() -> new ReviewNotRegisteredException(event.reviewId()));

    if (!ReviewProgress.of(snapshot.respondedAgents()).isComplete()) {
      return AgentResultOutcome.WAITING_FOR_AGENTS;
    }

    return consolidateReview.closeAndPublish(snapshot, ReviewStatus.COMPLETED)
        ? AgentResultOutcome.PUBLISHED
        : AgentResultOutcome.ALREADY_CLOSED;
  }
}
