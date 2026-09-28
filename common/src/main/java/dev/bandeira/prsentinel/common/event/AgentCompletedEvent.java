package dev.bandeira.prsentinel.common.event;

import java.util.List;
import java.util.UUID;

/** Publicado por cada agente ao terminar (com sucesso ou falha). */
public record AgentCompletedEvent(
    UUID reviewId,
    AgentType agent,
    Status status,
    List<Finding> findings,
    Usage usage,
    String errorMessage) {

  public enum Status {
    SUCCESS,
    FAILED
  }

  /** Métricas de consumo do LLM para observabilidade e custo. */
  public record Usage(String model, long inputTokens, long outputTokens, long latencyMs) {}
}
