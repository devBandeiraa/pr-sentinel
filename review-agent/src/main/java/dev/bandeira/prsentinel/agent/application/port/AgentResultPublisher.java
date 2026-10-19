package dev.bandeira.prsentinel.agent.application.port;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;

/** Publica o resultado do agente. Implementado em {@code infrastructure} sobre o RabbitMQ. */
public interface AgentResultPublisher {

  void publish(AgentCompletedEvent event);
}
