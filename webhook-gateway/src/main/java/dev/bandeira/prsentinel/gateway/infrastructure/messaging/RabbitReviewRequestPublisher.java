package dev.bandeira.prsentinel.gateway.infrastructure.messaging;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.gateway.application.port.ReviewRequestPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica {@code pr.review.requested} no topic exchange, propagando o {@code reviewId} como header
 * de correlação para que o fluxo possa ser rastreado pelos cinco serviços (ADR-001).
 */
@Component
public class RabbitReviewRequestPublisher implements ReviewRequestPublisher {

  private final RabbitTemplate rabbitTemplate;

  public RabbitReviewRequestPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @Override
  public void publish(ReviewRequestedEvent event) {
    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_REVIEW_REQUESTED,
        event,
        message -> {
          message
              .getMessageProperties()
              .setHeader(Topology.CORRELATION_HEADER, event.reviewId().toString());
          return message;
        });
  }
}
