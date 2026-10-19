package dev.bandeira.prsentinel.publisher.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * As validações existem porque a posição vem do LLM.
 *
 * <p>O {@code FindingFilter} do agente já descarta achado com linha inválida, mas isto aqui é a
 * última barreira antes de a posição virar requisição ao GitHub — e requisição malformada derruba a
 * review inteira, não só o comentário problemático.
 */
class InlineCommentTest {

  @Test
  void aceitaComentarioCompleto() {
    InlineComment comment = new InlineComment("src/App.java", 10, "**HIGH — algo**");

    assertThat(comment.path()).isEqualTo("src/App.java");
    assertThat(comment.line()).isEqualTo(10);
    assertThat(comment.body()).isEqualTo("**HIGH — algo**");
  }

  @Test
  void rejeitaCaminhoAusente() {
    assertThatThrownBy(() -> new InlineComment(null, 10, "corpo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InlineComment("  ", 10, "corpo"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaLinhaNaoPositiva() {
    // Numeração de diff começa em 1; zero é o modelo dizendo "não sei onde".
    assertThatThrownBy(() -> new InlineComment("a.java", 0, "corpo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InlineComment("a.java", -1, "corpo"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejeitaCorpoAusente() {
    assertThatThrownBy(() -> new InlineComment("a.java", 10, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InlineComment("a.java", 10, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reviewExigeResumoEAceitaListaDeComentariosNula() {
    assertThatThrownBy(() -> new RenderedReview(null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RenderedReview("  ", List.of()))
        .isInstanceOf(IllegalArgumentException.class);

    RenderedReview semComentario = new RenderedReview("## resumo", null);
    assertThat(semComentario.comments()).isEmpty();
    assertThat(semComentario.hasInlineComments()).isFalse();
  }

  @Test
  void reviewComComentarioSabeQueTemInline() {
    RenderedReview review =
        new RenderedReview("## resumo", List.of(new InlineComment("a.java", 1, "corpo")));

    assertThat(review.hasInlineComments()).isTrue();
  }
}
