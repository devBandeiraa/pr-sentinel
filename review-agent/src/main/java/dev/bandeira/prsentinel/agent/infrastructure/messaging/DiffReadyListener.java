package dev.bandeira.prsentinel.agent.infrastructure.messaging;

import dev.bandeira.prsentinel.agent.application.ReviewChunksUseCase;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code pr.diff.ready} da fila deste tipo de agente.
 *
 * <p>Cada tipo tem fila própria ligada à mesma routing key, então os três analisam em paralelo e um
 * agente lento ou fora do ar não bloqueia os outros (ADR-002).
 *
 * <p>Não deixa exceção escapar, de propósito: o caso de uso publica {@code FAILED} e o
 * processamento termina normalmente. Rejeitar a mensagem aqui faria o retry da fila chamar o LLM de
 * novo — pagando outra vez por um erro que o Spring AI já retentou — e, no fim, mandaria a review
 * para a DLQ sem que o orchestrator soubesse que este agente desistiu.
 */
@Component
public class DiffReadyListener {

  private static final Logger log = LoggerFactory.getLogger(DiffReadyListener.class);

  private final ReviewChunksUseCase reviewChunks;

  public DiffReadyListener(ReviewChunksUseCase reviewChunks) {
    this.reviewChunks = reviewChunks;
  }

  @RabbitListener(queues = "#{@agentQueueName}")
  public void onDiffReady(DiffReadyEvent event) {
    log.info(
        "Analisando review={} pr={} chunks={}",
        event.reviewId(),
        event.pullRequest().fullName(),
        event.chunks().size());

    AgentCompletedEvent result = reviewChunks.handle(event);

    if (result.status() == Status.SUCCESS) {
      log.info(
          "Agente {} terminou review={} achados={}",
          result.agent(),
          result.reviewId(),
          result.findings().size());
    } else {
      log.error(
          "Agente {} falhou review={} motivo={}",
          result.agent(),
          result.reviewId(),
          result.errorMessage());
    }
  }
}
