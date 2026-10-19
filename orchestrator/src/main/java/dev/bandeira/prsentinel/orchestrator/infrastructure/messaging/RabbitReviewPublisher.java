package dev.bandeira.prsentinel.orchestrator.infrastructure.messaging;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.orchestrator.application.port.ReviewPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica {@code pr.review.ready} no topic exchange, propagando o {@code reviewId} como header de
 * correlação para o fluxo poder ser rastreado pelos cinco serviços (ADR-001).
 */
@Component
public class RabbitReviewPublisher implements ReviewPublisher {

  private final RabbitTemplate rabbitTemplate;

  public RabbitReviewPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @Override
  public void publish(ReviewReadyEvent event) {
    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_REVIEW_READY,
        event,
        message -> {
          message
              .getMessageProperties()
              .setHeader(Topology.CORRELATION_HEADER, event.reviewId().toString());
          return message;
        });
  }
}
