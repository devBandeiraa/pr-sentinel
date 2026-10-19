package dev.bandeira.prsentinel.publisher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Se a review bloqueia merge ou não (ADR-009).
 *
 * <p>O default não bloqueia. Achado de LLM passou por um limiar de confiança, não por uma pessoa, e
 * travar o merge do time com base nisso é o caminho mais curto para alguém desligar o bot.
 */
class ReviewEventPolicyTest {

  private final ReviewEventPolicy padrao = new ReviewEventPolicy(false);
  private final ReviewEventPolicy modoDuro = new ReviewEventPolicy(true);

  @ParameterizedTest
  @EnumSource(Verdict.class)
  void noPadraoTodoVereditoSaiComoComentario(Verdict verdict) {
    assertThat(padrao.eventFor(verdict)).isEqualTo(GithubReviewEvent.COMMENT);
  }

  @Test
  void noModoDuroSoOPedidoDeMudancaBloqueia() {
    assertThat(modoDuro.eventFor(Verdict.CHANGES_REQUESTED))
        .isEqualTo(GithubReviewEvent.REQUEST_CHANGES);
    assertThat(modoDuro.eventFor(Verdict.ATTENTION)).isEqualTo(GithubReviewEvent.COMMENT);
    assertThat(modoDuro.eventFor(Verdict.APPROVED)).isEqualTo(GithubReviewEvent.COMMENT);
  }

  @Test
  void aprovarNuncaEhUmaOpcao() {
    // Bot que aprova dá a impressão de revisão humana feita. O enum não tem APPROVE de propósito.
    assertThat(GithubReviewEvent.values())
        .containsExactly(GithubReviewEvent.COMMENT, GithubReviewEvent.REQUEST_CHANGES);
  }
}
