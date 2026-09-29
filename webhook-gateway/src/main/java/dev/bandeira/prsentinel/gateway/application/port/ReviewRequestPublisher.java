package dev.bandeira.prsentinel.gateway.application.port;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;

/**
 * Publica o início da review. Implementado em {@code infrastructure} sobre o RabbitMQ.
 *
 * <p>Falha ao publicar deve lançar, não devolver erro: é a exceção que desfaz a transação e faz o
 * gateway responder 5xx, para o GitHub reentregar (ADR-005).
 */
public interface ReviewRequestPublisher {

  void publish(ReviewRequestedEvent event);
}
