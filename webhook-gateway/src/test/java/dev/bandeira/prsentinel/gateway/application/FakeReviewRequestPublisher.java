package dev.bandeira.prsentinel.gateway.application;

import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.gateway.application.port.ReviewRequestPublisher;
import java.util.ArrayList;
import java.util.List;

/**
 * Fake de {@link ReviewRequestPublisher} usado por {@link RequestReviewUseCaseTest}. Grava os
 * eventos publicados, e opcionalmente pode ser configurado para lançar — é assim que o teste prova
 * que a exceção do publisher propaga para fora do caso de uso (ADR-005: é o gatilho do rollback e
 * do 5xx).
 */
final class FakeReviewRequestPublisher implements ReviewRequestPublisher {

  private final List<ReviewRequestedEvent> published = new ArrayList<>();
  private RuntimeException failure;

  void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  List<ReviewRequestedEvent> published() {
    return List.copyOf(published);
  }

  @Override
  public void publish(ReviewRequestedEvent event) {
    if (failure != null) {
      throw failure;
    }
    published.add(event);
  }
}
