package dev.bandeira.prsentinel.orchestrator.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.orchestrator.application.port.ReviewPublisher;
import java.util.ArrayList;
import java.util.List;

/** Fake da porta de publicação: grava os eventos para o teste inspecionar. */
final class FakeReviewPublisher implements ReviewPublisher {

  private final List<ReviewReadyEvent> published = new ArrayList<>();

  List<ReviewReadyEvent> published() {
    return List.copyOf(published);
  }

  /**
   * O evento único publicado. Falha se forem zero ou mais de um: dois pr.review.ready viram dois
   * comentários no mesmo PR, que é justamente o que a transição condicional da ADR-008 evita.
   */
  ReviewReadyEvent onlyPublished() {
    assertThat(published).hasSize(1);
    return published.get(0);
  }

  @Override
  public void publish(ReviewReadyEvent event) {
    published.add(event);
  }
}
