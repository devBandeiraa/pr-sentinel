package dev.bandeira.prsentinel.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PurgeProcessedDeliveriesUseCaseTest {

  @Test
  void calculaOCutoffExatoAPartirDaRetencaoEDelegaParaAPorta() {
    Instant now = Instant.parse("2026-09-29T00:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    Duration retention = Duration.ofDays(30);
    FakeProcessedDeliveries processedDeliveries = new FakeProcessedDeliveries();
    processedDeliveries.purgeResultOf(4);

    PurgeProcessedDeliveriesUseCase useCase =
        new PurgeProcessedDeliveriesUseCase(processedDeliveries, clock, retention);

    int purged = useCase.purge();

    assertThat(purged).isEqualTo(4);
    assertThat(processedDeliveries.purgeCutoffs()).containsExactly(now.minus(retention));
  }
}
