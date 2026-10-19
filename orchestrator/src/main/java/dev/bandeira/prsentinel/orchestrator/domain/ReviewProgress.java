package dev.bandeira.prsentinel.orchestrator.domain;

import dev.bandeira.prsentinel.common.event.AgentType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Quem já respondeu numa review e quem falta.
 *
 * <p>A expectativa sai do próprio {@link AgentType}, e não de uma constante escrita à mão: pela
 * skill {@code add-review-agent}, somar um agente é acrescentar um valor ao enum, um prompt e um
 * container. Se a lista fosse fixa aqui, o agente novo nunca seria esperado e toda review passaria
 * a fechar antes de ele responder.
 */
public final class ReviewProgress {

  private final Set<AgentType> responded;

  private ReviewProgress(Set<AgentType> responded) {
    this.responded = responded;
  }

  public static ReviewProgress of(Set<AgentType> responded) {
    return new ReviewProgress(responded == null ? Set.of() : Set.copyOf(responded));
  }

  public static List<AgentType> expectedAgents() {
    return List.of(AgentType.values());
  }

  public boolean isComplete() {
    return responded.containsAll(expectedAgents());
  }

  /** Em ordem de declaração do enum, para o mesmo cenário render sempre o mesmo texto no PR. */
  public List<AgentType> missingAgents() {
    return Arrays.stream(AgentType.values()).filter(agent -> !responded.contains(agent)).toList();
  }
}
