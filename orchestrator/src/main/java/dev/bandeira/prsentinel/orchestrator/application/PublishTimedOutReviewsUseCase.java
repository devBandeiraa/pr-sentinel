package dev.bandeira.prsentinel.orchestrator.application;

import dev.bandeira.prsentinel.orchestrator.application.port.ReviewPublisher;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;
import dev.bandeira.prsentinel.orchestrator.domain.FindingConsolidator;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import dev.bandeira.prsentinel.orchestrator.domain.VerdictPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Publica as reviews que estouraram o timeout, com o que os agentes tiverem produzido (ADR-003).
 *
 * <p>Entregar uma review incompleta e assumida como incompleta é melhor que não entregar nada: o
 * autor do PR recebe os achados que existem e sabe exatamente o que não foi analisado.
 *
 * <p>Review sem resposta de ninguém termina em {@code FAILED}, não em {@code PARTIAL} — nos dois
 * casos o evento sai com {@code partial} verdadeiro, mas o status no banco distingue "faltou um
 * agente" de "nenhum agente respondeu", que é a diferença que importa para diagnosticar.
 */
public class PublishTimedOutReviewsUseCase {

  private final Reviews reviews;
  private final ConsolidateReview consolidateReview;
  private final Clock clock;
  private final Duration timeout;

  public PublishTimedOutReviewsUseCase(
      Reviews reviews,
      ReviewPublisher publisher,
      FindingConsolidator consolidator,
      VerdictPolicy verdictPolicy,
      Clock clock,
      Duration timeout) {
    this.reviews = reviews;
    this.consolidateReview =
        new ConsolidateReview(reviews, publisher, consolidator, verdictPolicy, clock);
    this.clock = clock;
    this.timeout = timeout;
  }

  /**
   * @return quantas reviews foram publicadas nesta passada
   */
  public int sweep() {
    Instant cutoff = Instant.now(clock).minus(timeout);
    List<UUID> expired = reviews.findOpenRequestedBefore(cutoff);

    int published = 0;
    for (UUID reviewId : expired) {
      Optional<ReviewSnapshot> snapshot = reviews.snapshot(reviewId);
      if (snapshot.isEmpty()) {
        // A review desapareceu entre a varredura e a leitura. Não há o que publicar, e insistir
        // não ajuda.
        continue;
      }
      if (consolidateReview.closeAndPublish(snapshot.get(), finalStatusFor(snapshot.get()))) {
        published++;
      }
    }
    return published;
  }

  private static ReviewStatus finalStatusFor(ReviewSnapshot snapshot) {
    return snapshot.respondedAgents().isEmpty() ? ReviewStatus.FAILED : ReviewStatus.PARTIAL;
  }
}
