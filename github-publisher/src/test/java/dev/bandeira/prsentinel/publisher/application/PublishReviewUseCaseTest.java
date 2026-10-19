package dev.bandeira.prsentinel.publisher.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.ReviewEventPolicy;
import dev.bandeira.prsentinel.publisher.domain.ReviewRenderer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Orquestração da publicação, com o {@link ReviewRenderer} e a {@link ReviewEventPolicy} reais —
 * eles são puros e têm teste próprio, e o que importa aqui é que estejam no caminho.
 */
class PublishReviewUseCaseTest {

  private static final UUID REVIEW_ID = UUID.randomUUID();
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");

  private final FakePullRequestReviews reviews = new FakePullRequestReviews();

  private final PublishReviewUseCase useCase =
      new PublishReviewUseCase(reviews, new ReviewRenderer(), new ReviewEventPolicy(false));

  @Test
  void postaAReviewComOsComentariosInline() {
    assertThat(useCase.handle(event(Verdict.CHANGES_REQUESTED, finding())))
        .isEqualTo(PublishOutcome.PUBLISHED);

    FakePullRequestReviews.PublishedReview published = reviews.onlyPublished();
    assertThat(published.pullRequest()).isEqualTo(PULL_REQUEST);
    assertThat(published.review().comments()).hasSize(1);
    assertThat(published.review().summary()).contains("PR Sentinel");
  }

  @Test
  void noPadraoPostaComoComentarioMesmoPedindoMudanca() {
    // ADR-009: o bot não bloqueia merge na configuração padrão.
    useCase.handle(event(Verdict.CHANGES_REQUESTED, finding()));

    assertThat(reviews.onlyPublished().event()).isEqualTo(GithubReviewEvent.COMMENT);
  }

  @Test
  void noModoDuroPedidoDeMudancaVaiComoRequestChanges() {
    PublishReviewUseCase modoDuro =
        new PublishReviewUseCase(reviews, new ReviewRenderer(), new ReviewEventPolicy(true));

    modoDuro.handle(event(Verdict.CHANGES_REQUESTED, finding()));

    assertThat(reviews.onlyPublished().event()).isEqualTo(GithubReviewEvent.REQUEST_CHANGES);
  }

  @Test
  void reviewJaPublicadaNaoEhPostadaDeNovo() {
    // A fila entrega ao menos uma vez: sem esta checagem, a reentrega gera dois comentários.
    reviews.seedAlreadyPublished(REVIEW_ID);

    assertThat(useCase.handle(event(Verdict.ATTENTION, finding())))
        .isEqualTo(PublishOutcome.ALREADY_PUBLISHED);

    assertThat(reviews.published()).isEmpty();
  }

  @Test
  void comentarioInlineRecusadoCaiParaReviewSoComResumo() {
    reviews.rejectInlineComments();

    assertThat(useCase.handle(event(Verdict.CHANGES_REQUESTED, finding())))
        .isEqualTo(PublishOutcome.PUBLISHED_WITHOUT_INLINE);

    assertThat(reviews.attempts()).as("a primeira tentativa foi recusada pelo GitHub").isEqualTo(2);

    FakePullRequestReviews.PublishedReview fallback = reviews.onlyPublished();
    assertThat(fallback.review().hasInlineComments()).isFalse();
    assertThat(fallback.review().summary())
        .as("sem inline, os achados precisam aparecer no corpo")
        .contains("src/main/java/Login.java:11");
  }

  @Test
  void oFallbackMantemOTipoDeEventoEscolhido() {
    PublishReviewUseCase modoDuro =
        new PublishReviewUseCase(reviews, new ReviewRenderer(), new ReviewEventPolicy(true));
    reviews.rejectInlineComments();

    modoDuro.handle(event(Verdict.CHANGES_REQUESTED, finding()));

    assertThat(reviews.onlyPublished().event()).isEqualTo(GithubReviewEvent.REQUEST_CHANGES);
  }

  @Test
  void seOFallbackTambemFalharAExcecaoSobe() {
    // Insistir aqui seria tentar o mesmo caminho duas vezes; quem decide é o retry da fila.
    reviews.rejectInlineComments();
    reviews.failSecondAttemptWith(new IllegalStateException("GitHub fora do ar"));

    assertThatThrownBy(() -> useCase.handle(event(Verdict.CHANGES_REQUESTED, finding())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reviewAprovadaSemAchadoTambemEhPostada() {
    // O autor do PR precisa saber que a análise rodou e não encontrou nada.
    assertThat(useCase.handle(event(Verdict.APPROVED))).isEqualTo(PublishOutcome.PUBLISHED);

    assertThat(reviews.onlyPublished().review().summary())
        .contains("nada a apontar")
        .contains("Nenhum achado");
  }

  @Test
  void reviewParcialAvisaNoCorpoQuemNaoRespondeu() {
    ReviewReadyEvent parcial =
        new ReviewReadyEvent(
            REVIEW_ID,
            PULL_REQUEST,
            Verdict.ATTENTION,
            true,
            List.of(AgentType.STANDARDS),
            List.of(finding()),
            5_000L);

    useCase.handle(parcial);

    assertThat(reviews.onlyPublished().review().summary())
        .contains("Review parcial")
        .contains("STANDARDS");
  }

  @Test
  void falhaQueNaoSejaRecusaDeInlinePropagaSemSegundaTentativa() {
    reviews.failWith(new IllegalStateException("401 do GitHub"));

    assertThatThrownBy(() -> useCase.handle(event(Verdict.ATTENTION, finding())))
        .isInstanceOf(IllegalStateException.class);

    assertThat(reviews.attempts()).isEqualTo(1);
  }

  private static ReviewReadyEvent event(Verdict verdict, Finding... findings) {
    return new ReviewReadyEvent(
        REVIEW_ID, PULL_REQUEST, verdict, false, List.of(), List.of(findings), 42_300L);
  }

  private static Finding finding() {
    return new Finding(
        "src/main/java/Login.java",
        11,
        Severity.CRITICAL,
        "SQL injection por concatenação",
        "O nome vem da requisição e entra direto na query.",
        "Use PreparedStatement com parâmetro.",
        0.95);
  }
}
