package dev.bandeira.prsentinel.agent;

import dev.bandeira.prsentinel.common.event.AgentType;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(@NotNull AgentType type, double minConfidence) {
  /** Nome do serviço usado para a fila: review-agent-security, etc. */
  public String serviceName() {
    return "review-agent-" + type.name().toLowerCase();
  }

  public String promptResource() {
    return "classpath:prompts/" + type.name().toLowerCase() + ".st";
  }
}
