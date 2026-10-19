package dev.bandeira.prsentinel.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Timeout da ADR-003: entregar uma review incompleta e assumida como incompleta é melhor que não
 * entregar nada.
 *
 * <p>Sem isto, um agente fora do ar deixa a review presa em {@code IN_PROGRESS} para sempre e o
 * autor do PR espera um retorno que nunca chega.
 */
class PublishTimedOutReviewsUseCaseTest {

  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");
  private static final Instant NOW = Instant.parse("2026-10-19T12:10:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final FakeReviews reviews = new FakeReviews();
  private final FakeReviewPublisher publisher = new FakeReviewPublisher();

  private final PublishTimedOutReviewsUseCase useCase =
      new PublishTimedOutReviewsUseCase(
          reviews,
          publisher,
          new FindingConsolidator(),
          new VerdictPolicy(),
          Clock.fixed(NOW, ZoneOffset.UTC),
          TIMEOUT);

  @Test
  void publicaParcialComOsAgentesQueResponderam() {
    UUID reviewId = reviewRequestedAt(NOW.minus(Duration.ofMinutes(6)));
    reviews.recordAgentResult(resultWith(reviewId, AgentType.SECURITY, finding(Severity.HIGH)));

    assertThat(useCase.sweep()).isEqualTo(1);

    ReviewReadyEvent published = publisher.onlyPublished();
    assertThat(published.partial()).isTrue();
    assertThat(published.missingAgents())
        .containsExactly(AgentType.PERFORMANCE, AgentType.STANDARDS);
    assertThat(published.findings()).hasSize(1);
    assertThat(published.verdict()).isEqualTo(Verdict.CHANGES_REQUESTED);
    assertThat(reviews.statusOf(reviewId)).isEqualTo(ReviewStatus.PARTIAL);
  }

  @Test
  void reviewSemNenhumaRespostaTerminaEmFailed() {
    UUID reviewId = reviewRequestedAt(NOW.minus(Duration.ofMinutes(6)));

    assertThat(useCase.sweep()).isEqualTo(1);

    ReviewReadyEvent published = publisher.onlyPublished();
    assertThat(published.partial()).isTrue();
    assertThat(published.missingAgents()).containsExactlyInAnyOrder(AgentType.values());
    assertThat(published.findings()).isEmpty();
    assertThat(reviews.statusOf(reviewId))
        .as("FAILED distingue 'nenhum agente respondeu' de 'faltou um'")
        .isEqualTo(ReviewStatus.FAILED);
  }

  @Test
  void naoTocaEmReviewDentroDoPrazo() {
    UUID reviewId = reviewRequestedAt(NOW.minus(Duration.ofMinutes(4)));

    assertThat(useCase.sweep()).isZero();
    assertThat(publisher.published()).isEmpty();
    assertThat(reviews.statusOf(reviewId)).isEqualTo(ReviewStatus.PENDING);
  }

  @Test
  void naoRepublicaReviewJaEncerrada() {
    UUID reviewId = reviewRequestedAt(NOW.minus(Duration.ofMinutes(6)));
    reviews.forceStatus(reviewId, ReviewStatus.COMPLETED);

    assertThat(useCase.sweep()).isZero();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void publicaTodasAsReviewsVencidasNaMesmaPassada() {
    reviewRequestedAt(NOW.minus(Duration.ofMinutes(6)));
    reviewRequestedAt(NOW.minus(Duration.ofMinutes(7)));
    reviewRequestedAt(NOW.minus(Duration.ofMinutes(1)));

    assertThat(useCase.sweep()).isEqualTo(2);
    assertThat(publisher.published()).hasSize(2);
  }

  @Test
  void umaSegundaPassadaNaoPublicaDeNovo() {
    reviewRequestedAt(NOW.minus(Duration.ofMinutes(6)));

    assertThat(useCase.sweep()).isEqualTo(1);
    assertThat(useCase.sweep()).isZero();
    assertThat(publisher.published()).hasSize(1);
  }

  @Test
  void calculaADuracaoAteOInstanteDaPublicacao() {
    reviewRequestedAt(NOW.minus(Duration.ofMinutes(6)));

    useCase.sweep();

    assertThat(publisher.onlyPublished().durationMs()).isEqualTo(Duration.ofMinutes(6).toMillis());
  }

  private UUID reviewRequestedAt(Instant requestedAt) {
    UUID reviewId = UUID.randomUUID();
    reviews.register(
        new ReviewRequestedEvent(reviewId, "delivery-" + reviewId, PULL_REQUEST, requestedAt));
    return reviewId;
  }

  private static AgentCompletedEvent resultWith(UUID reviewId, AgentType agent, Finding finding) {
    return new AgentCompletedEvent(reviewId, agent, Status.SUCCESS, List.of(finding), null, null);
  }

  private static Finding finding(Severity severity) {
    return new Finding("a.java", 10, severity, "t", "e", "s", 0.9);
  }
}
