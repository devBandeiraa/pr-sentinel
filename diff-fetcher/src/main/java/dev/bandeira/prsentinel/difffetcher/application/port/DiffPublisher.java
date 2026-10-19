package dev.bandeira.prsentinel.difffetcher.application.port;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent;

/**
 * Publica o diff pronto para análise. Implementado em {@code infrastructure} sobre o RabbitMQ.
 *
 * <p>Falha ao publicar deve lançar: é a exceção que faz o consumidor rejeitar a mensagem e deixar o
 * retry da fila, ou a DLQ, decidirem o destino.
 */
public interface DiffPublisher {

  void publish(DiffReadyEvent event);
}
