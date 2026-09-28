package dev.bandeira.prsentinel.publisher;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewReadyListener {

  private static final Logger log = LoggerFactory.getLogger(ReviewReadyListener.class);

  @RabbitListener(queues = "pr-sentinel." + GithubPublisherApplication.SERVICE)
  public void onReviewReady(ReviewReadyEvent event) {
    log.info(
        "Publicando review={} pr={} veredito={} achados={}",
        event.reviewId(),
        event.pullRequest().fullName(),
        event.verdict(),
        event.findings().size());

    // TODO fase 6:
    //  1. Montar o comentário-resumo em Markdown (veredito, tabela por agente/severidade, top 5)
    //  2. POST /repos/{owner}/{repo}/pulls/{number}/reviews com os comentários inline
    //  3. Respeitar rate limit (headers X-RateLimit-Remaining / Retry-After)
  }
}
