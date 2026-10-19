package dev.bandeira.prsentinel.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.orchestrator.domain.FindingConsolidator;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import dev.bandeira.prsentinel.orchestrator.domain.VerdictPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * O caminho principal da fase 5: os três agentes chegam em mensagens independentes e quem responde
 * por último fecha a review.
 *
 * <p>Usa o {@link FindingConsolidator} e o {@link VerdictPolicy} reais — são puros, têm teste
 * próprio, e o que interessa aqui é que eles estejam no caminho.
 */
class RecordAgentResultUseCaseTest {

  private static final UUID REVIEW_ID = UUID.randomUUID();
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");
  private static final Instant REQUESTED_AT = Instant.parse("2026-10-19T12:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-19T12:00:42Z");

  private final FakeReviews reviews = new FakeReviews();
  private final FakeReviewPublisher publisher = new FakeReviewPublisher();

  private final RecordAgentResultUseCase useCase =
      new RecordAgentResultUseCase(
          reviews,
          publisher,
          new FindingConsolidator(),
          new VerdictPolicy(),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void registraAReview() {
    reviews.register(new ReviewRequestedEvent(REVIEW_ID, "delivery-1", PULL_REQUEST, REQUESTED_AT));
  }

  @Test
  void osDoisPrimeirosAgentesApenasEsperam() {
    assertThat(useCase.handle(result(AgentType.SECURITY)))
        .isEqualTo(AgentResultOutcome.WAITING_FOR_AGENTS);
    assertThat(useCase.handle(result(AgentType.PERFORMANCE)))
        .isEqualTo(AgentResultOutcome.WAITING_FOR_AGENTS);

    assertThat(publisher.published()).isEmpty();
    assertThat(reviews.statusOf(REVIEW_ID)).isEqualTo(ReviewStatus.IN_PROGRESS);
  }

  @Test
  void oTerceiroAgenteFechaEPublicaAReview() {
    useCase.handle(result(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));

    assertThat(useCase.handle(result(AgentType.STANDARDS))).isEqualTo(AgentResultOutcome.PUBLISHED);

    ReviewReadyEvent published = publisher.onlyPublished();
    assertThat(published.reviewId()).isEqualTo(REVIEW_ID);
    assertThat(published.pullRequest()).isEqualTo(PULL_REQUEST);
    assertThat(published.partial()).isFalse();
    assertThat(published.missingAgents()).isEmpty();
    assertThat(reviews.statusOf(REVIEW_ID)).isEqualTo(ReviewStatus.COMPLETED);
  }

  @Test
  void aOrdemDeChegadaDosAgentesNaoImporta() {
    useCase.handle(result(AgentType.STANDARDS));
    useCase.handle(result(AgentType.SECURITY));

    assertThat(useCase.handle(result(AgentType.PERFORMANCE)))
        .isEqualTo(AgentResultOutcome.PUBLISHED);
  }

  @Test
  void calculaADuracaoAPartirDoPedido() {
    useCase.handle(result(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));
    useCase.handle(result(AgentType.STANDARDS));

    assertThat(publisher.onlyPublished().durationMs()).isEqualTo(42_000L);
  }

  @Test
  void deduplicaOsAchadosDosAgentesNaMesmaLinha() {
    // ADR-008: os três acham algo em Login.java:11 e só o mais severo vai para o PR.
    useCase.handle(resultWith(AgentType.SECURITY, finding(Severity.CRITICAL, 0.95)));
    useCase.handle(resultWith(AgentType.PERFORMANCE, finding(Severity.LOW, 0.8)));
    useCase.handle(resultWith(AgentType.STANDARDS, finding(Severity.MEDIUM, 0.7)));

    ReviewReadyEvent published = publisher.onlyPublished();
    assertThat(published.findings()).hasSize(1);
    assertThat(published.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
    assertThat(published.verdict()).isEqualTo(Verdict.CHANGES_REQUESTED);
  }

  @Test
  void reviewSemAchadoNenhumEhAprovada() {
    useCase.handle(result(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));
    useCase.handle(result(AgentType.STANDARDS));

    ReviewReadyEvent published = publisher.onlyPublished();
    assertThat(published.verdict()).isEqualTo(Verdict.APPROVED);
    assertThat(published.findings()).isEmpty();
    assertThat(published.partial()).isFalse();
  }

  @Test
  void resultadoRepetidoDoMesmoAgenteNaoContaDeNovo() {
    // A fila entrega ao menos uma vez: a mesma mensagem pode chegar duas vezes, e isso não pode
    // fazer a review fechar com dois agentes.
    useCase.handle(result(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));

    assertThat(useCase.handle(result(AgentType.SECURITY))).isEqualTo(AgentResultOutcome.DUPLICATE);

    assertThat(publisher.published()).isEmpty();
    assertThat(reviews.statusOf(REVIEW_ID)).isEqualTo(ReviewStatus.IN_PROGRESS);
  }

  @Test
  void agenteQueRespondeDepoisDoTimeoutNaoRepublica() {
    // ADR-003: o resultado tardio é registrado, mas a review já foi publicada como parcial.
    useCase.handle(result(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));
    reviews.forceStatus(REVIEW_ID, ReviewStatus.PARTIAL);

    assertThat(useCase.handle(result(AgentType.STANDARDS)))
        .isEqualTo(AgentResultOutcome.ALREADY_CLOSED);

    assertThat(publisher.published()).isEmpty();
    assertThat(reviews.statusOf(REVIEW_ID)).isEqualTo(ReviewStatus.PARTIAL);
  }

  @Test
  void agenteQueFalhouContaComoRespondido() {
    // Um agente que falhou respondeu: ele não vai mais responder, e esperar por ele só atrasaria
    // a review até o timeout.
    useCase.handle(failed(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));

    assertThat(useCase.handle(result(AgentType.STANDARDS))).isEqualTo(AgentResultOutcome.PUBLISHED);
    assertThat(publisher.onlyPublished().partial()).isFalse();
  }

  @Test
  void resultadoDeReviewDesconhecidaFalhaParaSerReentregue() {
    AgentCompletedEvent orfao =
        new AgentCompletedEvent(
            UUID.randomUUID(), AgentType.SECURITY, Status.SUCCESS, List.of(), null, null);

    assertThatThrownBy(() -> useCase.handle(orfao))
        .isInstanceOf(ReviewNotRegisteredException.class);

    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void encerraAntesDePublicar() {
    // Se publicasse primeiro, uma falha no encerramento deixaria a review aberta com o evento já
    // publicado — e o timeout publicaria de novo.
    useCase.handle(result(AgentType.SECURITY));
    useCase.handle(result(AgentType.PERFORMANCE));
    useCase.handle(result(AgentType.STANDARDS));

    assertThat(reviews.closedCalls()).hasSize(1);
    assertThat(reviews.closedCalls().get(0).status()).isEqualTo(ReviewStatus.COMPLETED);
    assertThat(reviews.closedCalls().get(0).verdict())
        .isEqualTo(publisher.onlyPublished().verdict());
  }

  private static AgentCompletedEvent result(AgentType agent) {
    return new AgentCompletedEvent(REVIEW_ID, agent, Status.SUCCESS, List.of(), null, null);
  }

  private static AgentCompletedEvent resultWith(AgentType agent, Finding finding) {
    return new AgentCompletedEvent(REVIEW_ID, agent, Status.SUCCESS, List.of(finding), null, null);
  }

  private static AgentCompletedEvent failed(AgentType agent) {
    return new AgentCompletedEvent(
        REVIEW_ID, agent, Status.FAILED, List.of(), null, "falha ao analisar o diff");
  }

  private static Finding finding(Severity severity, double confidence) {
    return new Finding(
        "src/main/java/Login.java",
        11,
        severity,
        "achado de " + severity,
        "explicação",
        "sugestão",
        confidence);
  }
}
