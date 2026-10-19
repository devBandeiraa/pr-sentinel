package dev.bandeira.prsentinel.publisher.application.port;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.RenderedReview;
import java.util.UUID;

/** A review no Pull Request. Implementado em {@code infrastructure} sobre a API do GitHub. */
public interface PullRequestReviews {

  /**
   * Se o PR já tem uma review com o marcador desta review (ADR-009).
   *
   * <p>É o que fecha o último buraco de comentário duplicado da cadeia: a fila entrega ao menos uma
   * vez, e uma falha entre postar e confirmar a mensagem faria a reentrega postar de novo.
   */
  boolean alreadyPublished(PullRequestRef pullRequest, UUID reviewId);

  /**
   * Posta a review.
   *
   * @throws dev.bandeira.prsentinel.publisher.application.InlineCommentsRejectedException quando o
   *     GitHub recusa os comentários posicionados — tipicamente porque a linha de um achado não faz
   *     parte do diff. A requisição inteira é recusada junto, e o caso de uso trata caindo para
   *     review sem inline.
   */
  void publish(PullRequestRef pullRequest, RenderedReview review, GithubReviewEvent event);
}
