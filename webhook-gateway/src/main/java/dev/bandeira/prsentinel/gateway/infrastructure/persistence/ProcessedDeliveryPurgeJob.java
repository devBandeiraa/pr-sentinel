package dev.bandeira.prsentinel.gateway.infrastructure.persistence;

import dev.bandeira.prsentinel.gateway.application.PurgeProcessedDeliveriesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara o expurgo dos registros de idempotência vencidos.
 *
 * <p>O agendamento é detalhe de framework, então mora aqui; a regra de retenção fica no caso de
 * uso.
 */
@Component
public class ProcessedDeliveryPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(ProcessedDeliveryPurgeJob.class);

  private final PurgeProcessedDeliveriesUseCase purgeProcessedDeliveries;

  public ProcessedDeliveryPurgeJob(PurgeProcessedDeliveriesUseCase purgeProcessedDeliveries) {
    this.purgeProcessedDeliveries = purgeProcessedDeliveries;
  }

  @Scheduled(
      fixedDelayString = "${gateway.idempotency.purge-interval:24h}",
      initialDelayString = "${gateway.idempotency.purge-interval:24h}")
  public void purge() {
    int removed = purgeProcessedDeliveries.purge();
    if (removed > 0) {
      log.info("Expurgo de idempotência removeu {} registros", removed);
    }
  }
}
