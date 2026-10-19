package dev.bandeira.prsentinel.orchestrator.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prazos da ADR-003.
 *
 * @param agentTimeout quanto a review espera pelos agentes antes de ser publicada parcial. Curto
 *     demais gera parciais desnecessárias, longo demais atrasa o retorno ao autor do PR — é ajuste
 *     empírico, para calibrar com a métrica de latência da fase 7.
 * @param timeoutCheckInterval de quanto em quanto tempo a varredura roda. Define a imprecisão do
 *     timeout: na prática a review é publicada entre {@code agentTimeout} e {@code agentTimeout +
 *     timeoutCheckInterval} depois do pedido.
 */
@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(Duration agentTimeout, Duration timeoutCheckInterval) {

  public OrchestratorProperties {
    agentTimeout = agentTimeout != null ? agentTimeout : Duration.ofMinutes(5);
    timeoutCheckInterval =
        timeoutCheckInterval != null ? timeoutCheckInterval : Duration.ofSeconds(30);
  }
}
