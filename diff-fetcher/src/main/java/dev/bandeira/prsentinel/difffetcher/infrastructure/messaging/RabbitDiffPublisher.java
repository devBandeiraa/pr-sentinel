package dev.bandeira.prsentinel.difffetcher.infrastructure.messaging;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.difffetcher.application.port.DiffPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica {@code pr.diff.ready} no topic exchange, propagando o {@code reviewId} como header de
 * correlação para o fluxo poder ser rastreado pelos cinco serviços (ADR-001).
 *
 * <p>A mensagem faz fan-out para as três filas de agente, uma por tipo, cada uma com DLQ própria.
 */
@Component
public class RabbitDiffPublisher implements DiffPublisher {

  private final RabbitTemplate rabbitTemplate;

  public RabbitDiffPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @Override
  public void publish(DiffReadyEvent event) {
    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_DIFF_READY,
        event,
        message -> {
          message
              .getMessageProperties()
              .setHeader(Topology.CORRELATION_HEADER, event.reviewId().toString());
          return message;
        });
  }
}
