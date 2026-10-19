package dev.bandeira.prsentinel.difffetcher.application;

import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.difffetcher.application.port.DiffPublisher;
import java.util.ArrayList;
import java.util.List;

/** Fake da porta de publicação: grava o que foi publicado para o teste inspecionar. */
final class FakeDiffPublisher implements DiffPublisher {

  private final List<DiffReadyEvent> published = new ArrayList<>();

  List<DiffReadyEvent> published() {
    return List.copyOf(published);
  }

  @Override
  public void publish(DiffReadyEvent event) {
    published.add(event);
  }
}
