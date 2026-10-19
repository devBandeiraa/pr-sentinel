package dev.bandeira.prsentinel.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.AgentType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Quem já respondeu e quem falta.
 *
 * <p>A lista de agentes esperados sai do próprio {@code AgentType}, e não de uma constante: pela
 * skill {@code add-review-agent}, somar um agente novo é acrescentar um valor ao enum, um prompt e
 * um container. Se a expectativa fosse escrita à mão aqui, o agente novo nunca seria esperado e
 * toda review passaria a fechar antes dele responder.
 */
class ReviewProgressTest {

  @Test
  void esperaTodosOsTiposDeAgenteDoEnum() {
    assertThat(ReviewProgress.expectedAgents())
        .containsExactlyInAnyOrder(AgentType.values())
        .hasSize(AgentType.values().length);
  }

  @Test
  void semNenhumaRespostaFaltamTodos() {
    ReviewProgress progress = ReviewProgress.of(Set.of());

    assertThat(progress.isComplete()).isFalse();
    assertThat(progress.missingAgents()).containsExactlyInAnyOrder(AgentType.values());
  }

  @Test
  void comDoisDeTresFaltaOTerceiro() {
    ReviewProgress progress = ReviewProgress.of(Set.of(AgentType.SECURITY, AgentType.PERFORMANCE));

    assertThat(progress.isComplete()).isFalse();
    assertThat(progress.missingAgents()).containsExactly(AgentType.STANDARDS);
  }

  @Test
  void comTodosNaoFaltaNinguem() {
    ReviewProgress progress = ReviewProgress.of(Set.of(AgentType.values()));

    assertThat(progress.isComplete()).isTrue();
    assertThat(progress.missingAgents()).isEmpty();
  }

  @Test
  void faltantesVemEmOrdemEstavel() {
    // A lista vai para o evento e daí para o comentário no PR: ordem instável faria o mesmo
    // cenário render textos diferentes.
    List<AgentType> primeira = ReviewProgress.of(Set.of(AgentType.SECURITY)).missingAgents();
    List<AgentType> segunda = ReviewProgress.of(Set.of(AgentType.SECURITY)).missingAgents();

    assertThat(primeira)
        .isEqualTo(segunda)
        .containsExactly(AgentType.PERFORMANCE, AgentType.STANDARDS);
  }

  @Test
  void respostaNulaEhTratadaComoNenhumaResposta() {
    assertThat(ReviewProgress.of(null).missingAgents())
        .containsExactlyInAnyOrder(AgentType.values());
  }
}
