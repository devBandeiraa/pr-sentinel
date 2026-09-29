package dev.bandeira.prsentinel.gateway.application;

import dev.bandeira.prsentinel.gateway.application.port.ProcessedDeliveries;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Expurga registros de idempotência mais velhos que a retenção configurada.
 *
 * <p>A retenção não existe só para controlar o tamanho da tabela — ela tem efeito funcional: o
 * GitHub permite reentrega manual pelo painel do App, e depois da janela de retenção essa reentrega
 * volta a ser processada em vez de ser descartada como duplicada (ADR-005).
 *
 * <p>Recebe o {@link Clock} em vez de chamar {@code Instant.now()} para que o cutoff seja testável.
 */
public class PurgeProcessedDeliveriesUseCase {

  private final ProcessedDeliveries processedDeliveries;
  private final Clock clock;
  private final Duration retention;

  public PurgeProcessedDeliveriesUseCase(
      ProcessedDeliveries processedDeliveries, Clock clock, Duration retention) {
    this.processedDeliveries = processedDeliveries;
    this.clock = clock;
    this.retention = retention;
  }

  public int purge() {
    Instant cutoff = Instant.now(clock).minus(retention);
    return processedDeliveries.purgeOlderThan(cutoff);
  }
}
