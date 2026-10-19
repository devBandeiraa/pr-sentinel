package dev.bandeira.prsentinel.agent.infrastructure.messaging;

import dev.bandeira.prsentinel.agent.application.port.AgentResultPublisher;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica {@code pr.agent.completed} no topic exchange, propagando o {@code reviewId} como header
 * de correlação para o fluxo poder ser rastreado pelos cinco serviços (ADR-001).
 */
@Component
public class RabbitAgentResultPublisher implements AgentResultPublisher {

  private final RabbitTemplate rabbitTemplate;

  public RabbitAgentResultPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @Override
  public void publish(AgentCompletedEvent event) {
    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_AGENT_COMPLETED,
        event,
        message -> {
          message
              .getMessageProperties()
              .setHeader(Topology.CORRELATION_HEADER, event.reviewId().toString());
          return message;
        });
  }
}
