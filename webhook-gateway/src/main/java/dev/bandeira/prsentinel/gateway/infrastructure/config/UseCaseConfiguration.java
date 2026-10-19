package dev.bandeira.prsentinel.gateway.infrastructure.config;

import dev.bandeira.prsentinel.gateway.application.PurgeProcessedDeliveriesUseCase;
import dev.bandeira.prsentinel.gateway.application.RequestReviewUseCase;
import dev.bandeira.prsentinel.gateway.application.port.ProcessedDeliveries;
import dev.bandeira.prsentinel.gateway.application.port.ReviewRequestPublisher;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Monta os casos de uso.
 *
 * <p>As classes de {@code application} não são anotadas com {@code @Component} de propósito: quem
 * decide como elas são construídas é a infraestrutura, e assim elas continuam instanciáveis num
 * teste unitário com um {@code new}.
 */
@Configuration
public class UseCaseConfiguration {

  /** Injetado nos casos de uso para que o instante seja fixável em teste. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public RequestReviewUseCase requestReviewUseCase(
      ProcessedDeliveries processedDeliveries, ReviewRequestPublisher publisher, Clock clock) {
    return new RequestReviewUseCase(processedDeliveries, publisher, clock);
  }

  @Bean
  public PurgeProcessedDeliveriesUseCase purgeProcessedDeliveriesUseCase(
      ProcessedDeliveries processedDeliveries, Clock clock, IdempotencyProperties properties) {
    return new PurgeProcessedDeliveriesUseCase(processedDeliveries, clock, properties.retention());
  }
}
