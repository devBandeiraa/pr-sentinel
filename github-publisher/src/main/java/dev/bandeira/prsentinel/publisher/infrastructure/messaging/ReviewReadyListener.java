package dev.bandeira.prsentinel.publisher.infrastructure.messaging;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.publisher.application.PublishOutcome;
import dev.bandeira.prsentinel.publisher.application.PublishReviewUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Consome {@code pr.review.ready} e publica a review no PR.
 *
 * <p>A decisão de retentar segue o mesmo critério do diff-fetcher, porque o interlocutor é o mesmo:
 * rate limit e erro de servidor passam com o tempo e merecem o retry da fila; PR fechado, App sem
 * permissão de escrita e credencial inválida são respostas estáveis, e insistir nelas só atrasa a
 * ida para a DLQ.
 */
@Component
public class ReviewReadyListener {

  private static final Logger log = LoggerFactory.getLogger(ReviewReadyListener.class);

  public static final String SERVICE = "github-publisher";

  private final PublishReviewUseCase publishReview;

  public ReviewReadyListener(PublishReviewUseCase publishReview) {
    this.publishReview = publishReview;
  }

  @RabbitListener(queues = "pr-sentinel." + SERVICE)
  public void onReviewReady(ReviewReadyEvent event) {
    log.info(
        "Publicando review={} pr={} veredito={} achados={} parcial={}",
        event.reviewId(),
        event.pullRequest().fullName(),
        event.verdict(),
        event.findings().size(),
        event.partial());

    try {
      PublishOutcome outcome = publishReview.handle(event);
      logOutcome(event, outcome);
    } catch (HttpClientErrorException e) {
      if (worthRetrying(e)) {
        throw e;
      }
      log.error(
          "GitHub recusou a publicação review={} status={}; indo para a DLQ",
          event.reviewId(),
          e.getStatusCode());
      throw new AmqpRejectAndDontRequeueException(
          "GitHub respondeu " + e.getStatusCode() + " para " + event.pullRequest().fullName(), e);
    }
  }

  private static void logOutcome(ReviewReadyEvent event, PublishOutcome outcome) {
    switch (outcome) {
      case PUBLISHED ->
          log.info(
              "Review publicada review={} pr={}", event.reviewId(), event.pullRequest().fullName());
      case PUBLISHED_WITHOUT_INLINE ->
          log.warn(
              "GitHub recusou os comentários inline review={}; publicada só com o resumo e os"
                  + " achados no corpo",
              event.reviewId());
      case ALREADY_PUBLISHED ->
          log.info("Review já estava no PR review={}; entrega repetida ignorada", event.reviewId());
    }
  }

  /** Rate limit do GitHub volta como {@code 403} com {@code x-ratelimit-remaining: 0}. */
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
