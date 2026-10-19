package dev.bandeira.prsentinel.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterReviewUseCaseTest {

  private static final UUID REVIEW_ID = UUID.randomUUID();

  private final FakeReviews reviews = new FakeReviews();
  private final RegisterReviewUseCase useCase = new RegisterReviewUseCase(reviews);

  @Test
  void registraAReviewComoPendente() {
    assertThat(useCase.handle(event())).isTrue();

    assertThat(reviews.statusOf(REVIEW_ID)).isEqualTo(ReviewStatus.PENDING);
    assertThat(reviews.snapshot(REVIEW_ID)).isPresent();
  }

  @Test
  void aMesmaMensagemDuasVezesNaoRegistraDeNovo() {
    // A fila entrega ao menos uma vez; a segunda entrega não pode criar uma segunda review.
    assertThat(useCase.handle(event())).isTrue();
    assertThat(useCase.handle(event())).isFalse();
  }

  @Test
  void guardaOPullRequestEOInstanteDoPedido() {
    useCase.handle(event());

    ReviewSnapshot snapshot = reviews.snapshot(REVIEW_ID).orElseThrow();
    assertThat(snapshot.pullRequest().fullName()).isEqualTo("devBandeiraa/pr-sentinel#7");
    assertThat(snapshot.requestedAt()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
    assertThat(snapshot.respondedAgents()).isEmpty();
    assertThat(snapshot.findings()).isEmpty();
  }

  private static ReviewRequestedEvent event() {
    return new ReviewRequestedEvent(
        REVIEW_ID,
        "delivery-1",
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"),
        Instant.parse("2026-10-19T12:00:00Z"));
  }
}
