package dev.bandeira.prsentinel.agent.infrastructure.config;

import dev.bandeira.prsentinel.common.event.AgentType;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * O que distingue um container de agente do outro (ADR-002).
 *
 * @param type vem de {@code AGENT_TYPE} e define a fila, o prompt e o campo {@code agent} do evento
 *     publicado. Obrigatório: um agente sem tipo não saberia o que analisar nem onde escutar.
 * @param minConfidence limiar da ADR-004. Achado abaixo disso é descartado antes de sair do agente.
 *     Mexer neste número muda o resultado das reviews, então é mudança para passar por eval — ver a
 *     skill {@code run-evals}.
 */
@Validated
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(@NotNull AgentType type, double minConfidence) {

  /** Nome do serviço usado na fila: {@code review-agent-security}, e assim por diante. */
  public String serviceName() {
    return "review-agent-" + type.name().toLowerCase(Locale.ROOT);
  }
}
