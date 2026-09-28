package dev.bandeira.prsentinel.common.event;

import java.util.List;
import java.util.UUID;

/** Publicado pelo orchestrator com o resultado consolidado. */
public record ReviewReadyEvent(
    UUID reviewId,
    PullRequestRef pullRequest,
    Verdict verdict,
    boolean partial,
    List<AgentType> missingAgents,
    List<Finding> findings,
    long durationMs) {

  public enum Verdict {
    APPROVED,
    ATTENTION,
    CHANGES_REQUESTED
  }
}
