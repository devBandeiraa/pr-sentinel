package dev.bandeira.prsentinel.publisher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewMarkerTest {

  private static final UUID REVIEW_ID = UUID.randomUUID();

  @Test
  void ehComentarioHtmlParaNaoAparecerRenderizadoNoPr() {
    assertThat(ReviewMarker.of(REVIEW_ID)).startsWith("<!--").endsWith("-->");
  }

  @Test
  void carregaOIdDaReview() {
    assertThat(ReviewMarker.of(REVIEW_ID)).contains(REVIEW_ID.toString());
  }

  @Test
  void reconheceOMarcadorNoMeioDeUmCorpoDeReview() {
    String body = "## PR Sentinel\n\ntexto\n\n" + ReviewMarker.of(REVIEW_ID);

    assertThat(ReviewMarker.isPresentIn(body, REVIEW_ID)).isTrue();
  }

  @Test
  void naoConfundeReviewsDiferentes() {
    String body = "texto " + ReviewMarker.of(UUID.randomUUID());

    assertThat(ReviewMarker.isPresentIn(body, REVIEW_ID)).isFalse();
  }

  @Test
  void corpoNuloOuSemMarcadorNaoCasa() {
    assertThat(ReviewMarker.isPresentIn(null, REVIEW_ID)).isFalse();
    assertThat(ReviewMarker.isPresentIn("review escrita por uma pessoa", REVIEW_ID)).isFalse();
  }
}
