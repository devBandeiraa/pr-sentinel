package dev.bandeira.prsentinel.difffetcher.infrastructure.messaging;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.difffetcher.application.DiffOutcome;
import dev.bandeira.prsentinel.difffetcher.application.FetchDiffUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Consome {@code pr.review.requested} e dispara a busca do diff.
 *
 * <p>Aqui mora a decisão de retentar ou não. A fila do serviço tem retry três vezes com backoff e
 * DLQ (ADR-001), mas insistir num erro que não vai mudar só atrasa a ida para a DLQ: PR apagado,
 * App sem permissão e credencial inválida são respostas estáveis do GitHub. Rate limit e erro de
 * servidor, ao contrário, passam — esses merecem o retry.
 */
@Component
public class ReviewRequestedListener {

  private static final Logger log = LoggerFactory.getLogger(ReviewRequestedListener.class);

  public static final String SERVICE = "diff-fetcher";

  private final FetchDiffUseCase fetchDiff;

  public ReviewRequestedListener(FetchDiffUseCase fetchDiff) {
    this.fetchDiff = fetchDiff;
  }

  @RabbitListener(queues = "pr-sentinel." + SERVICE)
  public void onReviewRequested(ReviewRequestedEvent event) {
    log.info("Buscando diff review={} pr={}", event.reviewId(), event.pullRequest().fullName());

    try {
      DiffOutcome outcome = fetchDiff.handle(event);
      log.info(
          "Diff publicado review={} arquivos={} revisaveis={} chunks={}",
          event.reviewId(),
          outcome.filesFetched(),
          outcome.filesReviewed(),
          outcome.chunks());
    } catch (HttpClientErrorException e) {
      if (worthRetrying(e)) {
        throw e;
      }
      log.error(
          "GitHub recusou a busca do diff review={} status={}; indo para a DLQ",
          event.reviewId(),
          e.getStatusCode());
      throw new AmqpRejectAndDontRequeueException(
          "GitHub respondeu " + e.getStatusCode() + " para " + event.pullRequest().fullName(), e);
    }
  }

  /**
   * Rate limit volta como {@code 403} com {@code x-ratelimit-remaining: 0}, e não como {@code 429}
   * — é uma peculiaridade da API do GitHub. Sem essa checagem, esbarrar no limite mandaria a review
   * direto para a DLQ em vez de esperar e tentar de novo.
   */
  private static boolean worthRetrying(HttpClientErrorException e) {
    HttpStatusCode status = e.getStatusCode();
    if (status.value() == 429) {
      return true;
    }
    if (status.value() != 403) {
      return false;
    }
    return "0"
        .equals(
            e.getResponseHeaders() == null
                ? null
                : e.getResponseHeaders().getFirst("x-ratelimit-remaining"));
  }
}
