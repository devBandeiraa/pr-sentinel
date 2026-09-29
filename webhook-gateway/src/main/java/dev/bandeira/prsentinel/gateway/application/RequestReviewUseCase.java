package dev.bandeira.prsentinel.gateway.application;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.gateway.application.port.ProcessedDeliveries;
import dev.bandeira.prsentinel.gateway.application.port.ReviewRequestPublisher;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decide o que fazer com uma entrega de webhook já validada.
 *
 * <p>A ordem importa e é o ponto central da ADR-005: <b>reserva antes de publicar</b>. Se
 * publicássemos primeiro, uma falha na reserva deixaria evento duplicado; reservando primeiro, uma
 * falha na publicação desfaz a reserva junto e o GitHub reentrega.
 *
 * <p>O {@code @Transactional} aqui é uma exceção consciente ao CLAUDE.md, que reserva o framework à
 * camada {@code infrastructure}: o limite transacional precisa envolver reserva e publicação
 * juntas, e esse limite é do caso de uso. Está registrado nas consequências da ADR-005.
 */
public class RequestReviewUseCase {

  private final ProcessedDeliveries processedDeliveries;
  private final ReviewRequestPublisher publisher;
  private final Clock clock;

  public RequestReviewUseCase(
      ProcessedDeliveries processedDeliveries, ReviewRequestPublisher publisher, Clock clock) {
    this.processedDeliveries = processedDeliveries;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Transactional
  public DeliveryOutcome handle(WebhookDelivery delivery) {
    if (!delivery.action().triggersReview()) {
      return DeliveryOutcome.IGNORED_ACTION;
    }

    UUID reviewId = UUID.randomUUID();
    boolean reserved =
        processedDeliveries.register(delivery.deliveryId(), reviewId, delivery.action());
    if (!reserved) {
      return DeliveryOutcome.DUPLICATE;
    }

    publisher.publish(
        new ReviewRequestedEvent(
            reviewId, delivery.deliveryId().value(), delivery.pullRequest(), Instant.now(clock)));

    return DeliveryOutcome.PUBLISHED;
  }
}
