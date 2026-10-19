package dev.bandeira.prsentinel.publisher.application;

/**
 * O GitHub recusou os comentários posicionados.
 *
 * <p>Acontece quando a linha de um achado não faz parte do diff — e a linha vem do LLM, então não
 * há garantia nenhuma. Basta uma estar fora para a requisição inteira ser recusada e nenhum
 * comentário ser postado, por isso o caso de uso reage caindo para a review sem inline (ADR-009).
 *
 * <p>Não leva a mensagem original do GitHub como texto: ela repete o corpo da requisição, que
 * contém o código do PR.
 */
public class InlineCommentsRejectedException extends RuntimeException {

  public InlineCommentsRejectedException() {
    super("GitHub recusou os comentários inline da review");
  }
}
