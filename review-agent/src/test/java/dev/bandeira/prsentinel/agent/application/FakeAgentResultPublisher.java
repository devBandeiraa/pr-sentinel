package dev.bandeira.prsentinel.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.agent.application.port.AgentResultPublisher;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import java.util.ArrayList;
import java.util.List;

/** Fake da porta de publicação: grava os eventos para o teste inspecionar. */
final class FakeAgentResultPublisher implements AgentResultPublisher {

  private final List<AgentCompletedEvent> published = new ArrayList<>();

  List<AgentCompletedEvent> published() {
    return List.copyOf(published);
  }

  /**
   * O evento único publicado. Falha se forem zero ou mais de um — publicar dois viraria duas
   * contribuições para a mesma review no orchestrator.
   */
  AgentCompletedEvent onlyPublished() {
    assertThat(published).hasSize(1);
    return published.get(0);
  }

  @Override
  public void publish(AgentCompletedEvent event) {
    published.add(event);
  }
}
