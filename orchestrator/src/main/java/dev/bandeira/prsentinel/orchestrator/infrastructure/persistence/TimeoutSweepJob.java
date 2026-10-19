package dev.bandeira.prsentinel.orchestrator.infrastructure.persistence;

import dev.bandeira.prsentinel.orchestrator.application.PublishTimedOutReviewsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara a varredura de reviews vencidas.
 *
 * <p>O agendamento é detalhe de framework e mora aqui; a regra de prazo fica no caso de uso. É o
 * mesmo arranjo do expurgo de idempotência no gateway.
 *
 * <p>Varredura de banco, e não mensagem com atraso no RabbitMQ: a ADR-003 já decidiu que o controle
 * de timeout precisa sobreviver a restart, e estado durável é justamente o que o banco dá.
 */
@Component
public class TimeoutSweepJob {

  private static final Logger log = LoggerFactory.getLogger(TimeoutSweepJob.class);

  private final PublishTimedOutReviewsUseCase publishTimedOutReviews;

  public TimeoutSweepJob(PublishTimedOutReviewsUseCase publishTimedOutReviews) {
    this.publishTimedOutReviews = publishTimedOutReviews;
  }

  @Scheduled(
      fixedDelayString = "${orchestrator.timeout-check-interval:30s}",
      initialDelayString = "${orchestrator.timeout-check-interval:30s}")
  public void sweep() {
    int published = publishTimedOutReviews.sweep();
    if (published > 0) {
      log.warn("Timeout publicou {} review(s) parcial(is) (ADR-003)", published);
    }
  }
}
