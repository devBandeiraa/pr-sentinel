package dev.bandeira.prsentinel.orchestrator.application.port;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;

/** Publica a review consolidada. Implementado em {@code infrastructure} sobre o RabbitMQ. */
public interface ReviewPublisher {

  void publish(ReviewReadyEvent event);
}
