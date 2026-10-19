package dev.bandeira.prsentinel.orchestrator.application;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.orchestrator.application.port.ReviewPublisher;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;
import dev.bandeira.prsentinel.orchestrator.domain.FindingConsolidator;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewProgress;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import dev.bandeira.prsentinel.orchestrator.domain.VerdictPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fecha uma review e publica o resultado, se for esta chamada que a fechar.
 *
 * <p>Reunido num colaborador próprio porque os dois caminhos que terminam uma review — o terceiro
 * agente respondendo e o timeout estourando — fazem exatamente a mesma coisa, mudando só o status
 * final. Duplicar isso nos dois casos de uso seria convidar os dois a divergirem.
 *
 * <p>A ordem é deliberada: <b>encerra antes de publicar</b>. Se publicasse primeiro, uma falha no
 * encerramento deixaria a review aberta com o evento já publicado, e o timeout publicaria de novo.
 */
class ConsolidateReview {

  private final Reviews reviews;
  private final ReviewPublisher publisher;
  private final FindingConsolidator consolidator;
  private final VerdictPolicy verdictPolicy;
  private final Clock clock;

  ConsolidateReview(
      Reviews reviews,
      ReviewPublisher publisher,
      FindingConsolidator consolidator,
      VerdictPolicy verdictPolicy,
      Clock clock) {
    this.reviews = reviews;
    this.publisher = publisher;
    this.consolidator = consolidator;
    this.verdictPolicy = verdictPolicy;
    this.clock = clock;
  }

  /**
   * @return {@code true} se esta chamada encerrou e publicou a review
   */
  boolean closeAndPublish(ReviewSnapshot snapshot, ReviewStatus finalStatus) {
    List<Finding> findings = consolidator.consolidate(snapshot.findings());
    Verdict verdict = verdictPolicy.decide(findings);
    Instant now = Instant.now(clock);

    if (!reviews.close(snapshot.reviewId(), finalStatus, verdict, now)) {
      return false;
    }

    ReviewProgress progress = ReviewProgress.of(snapshot.respondedAgents());
    publisher.publish(
        new ReviewReadyEvent(
            snapshot.reviewId(),
            snapshot.pullRequest(),
            verdict,
            !progress.isComplete(),
            progress.missingAgents(),
            findings,
            Duration.between(snapshot.requestedAt(), now).toMillis()));
    return true;
  }
}
