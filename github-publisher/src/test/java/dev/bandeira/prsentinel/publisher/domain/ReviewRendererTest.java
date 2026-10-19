package dev.bandeira.prsentinel.publisher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * O texto que vai para o PR.
 *
 * <p>É o único lugar do sistema que escreve para pessoas, e duas coisas aqui não são cosméticas:
 * review parcial precisa dizer quais agentes faltaram, porque ninguém pode confundir "nenhum
 * problema de segurança" com "o agente de segurança não rodou" (ADR-003); e o texto precisa assumir
 * que a origem é um LLM, com falso positivo possível (ADR-004).
 */
class ReviewRendererTest {

  private static final UUID REVIEW_ID = UUID.fromString("4f8c9a1e-0000-4000-8000-000000000001");
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");

  private final ReviewRenderer renderer = new ReviewRenderer();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "CHANGES_REQUESTED, mudanças solicitadas",
    "ATTENTION, pontos de atenção",
    "APPROVED, nada a apontar"
  })
  void oTituloVemDoVeredito(Verdict verdict, String expectedHeadline) {
    RenderedReview rendered = renderer.render(event(verdict, false, List.of(), finding()));

    assertThat(rendered.summary()).contains("PR Sentinel — " + expectedHeadline);
  }

  @Test
  void oResumoTrazOMarcadorDeIdempotencia() {
    // Sem o marcador, a reentrega da mensagem posta a review de novo (ADR-009).
    RenderedReview rendered = renderer.render(event(Verdict.APPROVED, false, List.of()));

    assertThat(rendered.summary()).contains(ReviewMarker.of(REVIEW_ID));
    assertThat(ReviewMarker.isPresentIn(rendered.summary(), REVIEW_ID)).isTrue();
  }

  @Test
  void avisaQuaisAgentesNaoResponderamNaReviewParcial() {
    RenderedReview rendered =
        renderer.render(
            event(
                Verdict.ATTENTION,
                true,
                List.of(AgentType.PERFORMANCE, AgentType.STANDARDS),
                finding()));

    assertThat(rendered.summary())
        .contains("Review parcial")
        .contains("PERFORMANCE")
        .contains("STANDARDS");
  }

  @Test
  void reviewCompletaNaoFalaDeAgenteFaltando() {
    RenderedReview rendered =
        renderer.render(event(Verdict.ATTENTION, false, List.of(), finding()));

    assertThat(rendered.summary()).doesNotContain("parcial");
  }

  @Test
  void contaOsAchadosPorSeveridadeEmOrdemDeGravidade() {
    RenderedReview rendered =
        renderer.render(
            event(
                Verdict.CHANGES_REQUESTED,
                false,
                List.of(),
                finding("a.java", 1, Severity.MEDIUM),
                finding("b.java", 2, Severity.CRITICAL),
                finding("c.java", 3, Severity.CRITICAL)));

    String summary = rendered.summary();
    assertThat(summary).contains("| CRITICAL | 2 |").contains("| MEDIUM | 1 |");
    assertThat(summary.indexOf("CRITICAL"))
        .as("o mais grave aparece antes na tabela")
        .isLessThan(summary.indexOf("MEDIUM"));
    assertThat(summary).doesNotContain("| HIGH |");
  }

  @Test
  void reviewSemAchadoDizQueNadaPassouDoLimiar() {
    RenderedReview rendered = renderer.render(event(Verdict.APPROVED, false, List.of()));

    assertThat(rendered.summary()).contains("Nenhum achado acima do limiar");
    assertThat(rendered.comments()).isEmpty();
  }

  @Test
  void avisaQueOsAchadosVemDeLlmEPodemSerFalsoPositivo() {
    RenderedReview rendered =
        renderer.render(event(Verdict.ATTENTION, false, List.of(), finding()));

    assertThat(rendered.summary()).contains("LLM").contains("falso positivo");
  }

  @Test
  void umComentarioInlinePorAchadoNaPosicaoDoAchado() {
    RenderedReview rendered =
        renderer.render(
            event(
                Verdict.CHANGES_REQUESTED,
                false,
                List.of(),
                finding("src/main/java/Login.java", 11, Severity.CRITICAL),
                finding("src/main/java/Repo.java", 42, Severity.LOW)));

    assertThat(rendered.comments())
        .extracting(InlineComment::path, InlineComment::line)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("src/main/java/Login.java", 11),
            org.assertj.core.groups.Tuple.tuple("src/main/java/Repo.java", 42));
  }

  @Test
  void oComentarioInlineTrazSeveridadeTituloExplicacaoSugestaoEConfianca() {
    RenderedReview rendered =
        renderer.render(event(Verdict.CHANGES_REQUESTED, false, List.of(), finding()));

    String body = rendered.comments().get(0).body();
    assertThat(body)
        .contains("CRITICAL")
        .contains("SQL injection por concatenação")
        .contains("O nome vem da requisição")
        .contains("Sugestão")
        .contains("Use PreparedStatement")
        .contains("confiança 0,95".replace(',', '.'));
  }

  @Test
  void achadoSemSugestaoNaoRenderizaSecaoDeSugestaoVazia() {
    Finding semSugestao =
        new Finding("a.java", 10, Severity.MEDIUM, "título", "explicação", null, 0.8);

    RenderedReview rendered =
        renderer.render(event(Verdict.ATTENTION, false, List.of(), semSugestao));

    assertThat(rendered.comments().get(0).body()).doesNotContain("Sugestão");
  }

  @Test
  void oFallbackListaOsAchadosNoCorpoENaoTemInline() {
    // Sem inline e sem lista, a review não diria onde estão os problemas (ADR-009).
    RenderedReview rendered =
        renderer.renderWithFindingsInBody(
            event(
                Verdict.CHANGES_REQUESTED,
                false,
                List.of(),
                finding("src/main/java/Login.java", 11, Severity.CRITICAL)));

    assertThat(rendered.hasInlineComments()).isFalse();
    assertThat(rendered.summary())
        .contains("src/main/java/Login.java:11")
        .contains("CRITICAL")
        .contains(ReviewMarker.of(REVIEW_ID));
  }

  @Test
  void oCaminhoNormalNaoRepeteAListaDeAchadosNoCorpo() {
    RenderedReview rendered =
        renderer.render(
            event(
                Verdict.CHANGES_REQUESTED,
                false,
                List.of(),
                finding("src/main/java/Login.java", 11, Severity.CRITICAL)));

    assertThat(rendered.summary())
        .as("com comentário inline, repetir a lista no corpo duplica a informação")
        .doesNotContain("src/main/java/Login.java:11");
  }

  @ParameterizedTest(name = "{0} ms -> {1}")
  @CsvSource({"0, 0s", "4200, 4s", "42300, 42s", "61000, 1min 01s", "125000, 2min 05s"})
  void mostraADuracaoEmFormatoLegivel(long durationMs, String expected) {
    ReviewReadyEvent event =
        new ReviewReadyEvent(
            REVIEW_ID, PULL_REQUEST, Verdict.APPROVED, false, List.of(), List.of(), durationMs);

    assertThat(renderer.render(event).summary()).contains(expected);
  }

  private static ReviewReadyEvent event(
      Verdict verdict, boolean partial, List<AgentType> missing, Finding... findings) {
    return new ReviewReadyEvent(
        REVIEW_ID, PULL_REQUEST, verdict, partial, missing, List.of(findings), 42_300L);
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

  private static Finding finding(String file, int line, Severity severity) {
    return new Finding(
        file, line, severity, "título de " + severity, "explicação", "sugestão", 0.9);
  }
}
