package dev.bandeira.prsentinel.orchestrator.application;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fake em memória da porta de persistência.
 *
 * <p>Reproduz as três semânticas que o caso de uso depende e que um stub esconderia: registro
 * idempotente, resultado de agente único por {@code review_id + agent}, e encerramento condicional
 * — {@link #close} só aceita quando a review está aberta, igual ao {@code UPDATE} condicional da
 * ADR-008.
 */
final class FakeReviews implements Reviews {

  private final Map<UUID, Row> rows = new LinkedHashMap<>();
  private final List<ClosedCall> closedCalls = new ArrayList<>();

  record ClosedCall(UUID reviewId, ReviewStatus status, Verdict verdict) {}

  private static final class Row {
    private final PullRequestRef pullRequest;
    private final Instant requestedAt;
    private final Set<AgentType> responded = new LinkedHashSet<>();
    private final List<Finding> findings = new ArrayList<>();
    private ReviewStatus status = ReviewStatus.PENDING;

    private Row(PullRequestRef pullRequest, Instant requestedAt) {
      this.pullRequest = pullRequest;
      this.requestedAt = requestedAt;
    }
  }

  List<ClosedCall> closedCalls() {
    return List.copyOf(closedCalls);
  }

  ReviewStatus statusOf(UUID reviewId) {
    return rows.get(reviewId).status;
  }

  /** Força o status, para montar o cenário de review já encerrada pelo timeout. */
  void forceStatus(UUID reviewId, ReviewStatus status) {
    rows.get(reviewId).status = status;
  }

  @Override
  public boolean register(ReviewRequestedEvent event) {
    if (rows.containsKey(event.reviewId())) {
      return false;
    }
    rows.put(event.reviewId(), new Row(event.pullRequest(), event.requestedAt()));
    return true;
  }

  @Override
  public boolean recordAgentResult(AgentCompletedEvent event) {
    Row row = rows.get(event.reviewId());
    if (row == null) {
      throw new ReviewNotRegisteredException(event.reviewId());
    }
    if (!row.responded.add(event.agent())) {
      return false;
    }
    if (event.findings() != null) {
      row.findings.addAll(event.findings());
    }
    if (row.status == ReviewStatus.PENDING) {
      row.status = ReviewStatus.IN_PROGRESS;
    }
    return true;
  }

  @Override
  public Optional<ReviewSnapshot> snapshot(UUID reviewId) {
    return Optional.ofNullable(rows.get(reviewId))
        .map(
            row ->
                new ReviewSnapshot(
                    reviewId,
                    row.pullRequest,
                    Set.copyOf(row.responded),
                    List.copyOf(row.findings),
                    row.requestedAt));
  }

  @Override
  public boolean close(
      UUID reviewId, ReviewStatus finalStatus, Verdict verdict, Instant completedAt) {
    Row row = rows.get(reviewId);
    if (row == null || row.status.isFinished()) {
      return false;
    }
    row.status = finalStatus;
    closedCalls.add(new ClosedCall(reviewId, finalStatus, verdict));
    return true;
  }

  @Override
  public List<UUID> findOpenRequestedBefore(Instant cutoff) {
    return rows.entrySet().stream()
        .filter(entry -> entry.getValue().status.isOpen())
        .filter(entry -> entry.getValue().requestedAt.isBefore(cutoff))
        .map(Map.Entry::getKey)
        .toList();
  }
}
