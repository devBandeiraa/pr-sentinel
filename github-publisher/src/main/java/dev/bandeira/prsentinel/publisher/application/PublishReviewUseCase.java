package dev.bandeira.prsentinel.publisher.application;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.publisher.application.port.PullRequestReviews;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.ReviewEventPolicy;
import dev.bandeira.prsentinel.publisher.domain.ReviewRenderer;

/**
 * Transforma a review consolidada em uma review no Pull Request.
 *
 * <p>É o primeiro ponto do sistema que escreve no repositório de alguém, e isso muda o peso dos
 * erros: até aqui, falhar significava review que não acontece; aqui pode significar comentário
 * repetido no PR de outra pessoa. Daí os dois cuidados abaixo.
 *
 * <p><b>Checa antes de postar.</b> A fila entrega ao menos uma vez, e o marcador no corpo da review
 * é o que permite reconhecer o próprio trabalho já feito (ADR-009).
 *
 * <p><b>Cai para review sem inline em vez de desistir.</b> Um achado com linha fora do diff faz o
 * GitHub recusar a requisição inteira. Uma review menos navegável é muito melhor que nenhuma.
 */
public class PublishReviewUseCase {

  private final PullRequestReviews reviews;
  private final ReviewRenderer renderer;
  private final ReviewEventPolicy eventPolicy;

  public PublishReviewUseCase(
      PullRequestReviews reviews, ReviewRenderer renderer, ReviewEventPolicy eventPolicy) {
    this.reviews = reviews;
    this.renderer = renderer;
    this.eventPolicy = eventPolicy;
  }

  public PublishOutcome handle(ReviewReadyEvent event) {
    if (reviews.alreadyPublished(event.pullRequest(), event.reviewId())) {
      return PublishOutcome.ALREADY_PUBLISHED;
    }

    GithubReviewEvent reviewEvent = eventPolicy.eventFor(event.verdict());

    try {
      reviews.publish(event.pullRequest(), renderer.render(event), reviewEvent);
      return PublishOutcome.PUBLISHED;
    } catch (InlineCommentsRejectedException e) {
      // Uma segunda tentativa, sem inline. Se esta falhar também, a exceção sobe: o retry da fila
      // decide, e insistir aqui seria tentar o mesmo caminho duas vezes.
      reviews.publish(event.pullRequest(), renderer.renderWithFindingsInBody(event), reviewEvent);
      return PublishOutcome.PUBLISHED_WITHOUT_INLINE;
    }
  }
}
