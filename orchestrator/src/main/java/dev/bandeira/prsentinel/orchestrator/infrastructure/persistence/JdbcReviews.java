package dev.bandeira.prsentinel.orchestrator.infrastructure.persistence;

import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.orchestrator.application.ReviewNotRegisteredException;
import dev.bandeira.prsentinel.orchestrator.application.ReviewSnapshot;
import dev.bandeira.prsentinel.orchestrator.application.port.Reviews;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estado das reviews no Postgres.
 *
 * <p>JDBC, e não JPA como o POM da fase 1 previa. O motivo é o mesmo que levou o gateway a essa
 * escolha na ADR-005, agora com mais força: as três operações que sustentam a correção deste
 * serviço são SQL que o JPA não expressa — dois {@code INSERT ... ON CONFLICT DO NOTHING} e um
 * {@code UPDATE} condicional por status. Com JPA, cada uma viraria exceção de constraint usada como
 * fluxo de controle dentro de uma transação já marcada para rollback.
 *
 * <p>As três merecem ficar visíveis em revisão, porque é nelas que mora a diferença entre uma
 * publicação e duas no mesmo PR.
 */
@Repository
public class JdbcReviews implements Reviews {

  private static final String INSERT_REVIEW =
      """
      INSERT INTO review (id, delivery_id, installation_id, owner, repo, pr_number, head_sha,
                          status, requested_at)
      VALUES (:id, :deliveryId, :installationId, :owner, :repo, :prNumber, :headSha, :status,
              :requestedAt)
      ON CONFLICT DO NOTHING
      """;

  // ON CONFLICT sem alvo cobre qualquer unicidade da tabela, não só a chave primária. É de
  // propósito: delivery_id também é único, e uma reentrega manual depois da retenção do gateway
  // chega com reviewId novo e delivery_id repetido (ADR-005). Sem isso, o insert explodiria e a
  // mensagem iria para a DLQ; com isso, o serviço segue de pé e o caso aparece como review que
  // nunca recebe resultado.

  private static final String INSERT_AGENT_RESULT =
      """
      INSERT INTO agent_result (review_id, agent, status, model, input_tokens, output_tokens,
                                latency_ms, error_message, completed_at)
      VALUES (:reviewId, :agent, :status, :model, :inputTokens, :outputTokens, :latencyMs,
              :errorMessage, :completedAt)
      ON CONFLICT (review_id, agent) DO NOTHING
      RETURNING id
      """;

  private static final String INSERT_FINDING =
      """
      INSERT INTO finding (agent_result_id, file, line, severity, title, explanation, suggestion,
                           confidence)
      VALUES (:agentResultId, :file, :line, :severity, :title, :explanation, :suggestion,
              :confidence)
      """;

  private static final String START_PROGRESS =
      "UPDATE review SET status = 'IN_PROGRESS' WHERE id = :id AND status = 'PENDING'";

  private static final String SELECT_REVIEW =
      """
      SELECT installation_id, owner, repo, pr_number, head_sha, requested_at
      FROM review WHERE id = :id
      """;

  private static final String SELECT_RESPONDED_AGENTS =
      "SELECT agent FROM agent_result WHERE review_id = :reviewId";

  private static final String SELECT_FINDINGS =
      """
      SELECT f.file, f.line, f.severity, f.title, f.explanation, f.suggestion, f.confidence
      FROM finding f
      JOIN agent_result ar ON ar.id = f.agent_result_id
      WHERE ar.review_id = :reviewId
      ORDER BY ar.id, f.id
      """;

  private static final String CLOSE_REVIEW =
      """
      UPDATE review
      SET status = :status, verdict = :verdict, completed_at = :completedAt
      WHERE id = :id AND status IN ('PENDING', 'IN_PROGRESS')
      """;

  private static final String SELECT_OPEN_BEFORE =
      """
      SELECT id FROM review
      WHERE status IN ('PENDING', 'IN_PROGRESS') AND requested_at < :cutoff
      ORDER BY requested_at
      """;

  private final JdbcClient jdbcClient;

  public JdbcReviews(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public boolean register(ReviewRequestedEvent event) {
    PullRequestRef pullRequest = event.pullRequest();
    int inserted =
        jdbcClient
            .sql(INSERT_REVIEW)
            .param("id", event.reviewId())
            .param("deliveryId", event.deliveryId())
            .param("installationId", pullRequest.installationId())
            .param("owner", pullRequest.owner())
            .param("repo", pullRequest.repo())
            .param("prNumber", pullRequest.number())
            .param("headSha", pullRequest.headSha())
            .param("status", ReviewStatus.PENDING.name())
            .param("requestedAt", at(event.requestedAt()))
            .update();
    return inserted == 1;
  }

  @Override
  @Transactional
  public boolean recordAgentResult(AgentCompletedEvent event) {
    Optional<Long> agentResultId = insertAgentResult(event);
    if (agentResultId.isEmpty()) {
      return false;
    }

    for (Finding finding : safe(event.findings())) {
      insertFinding(agentResultId.get(), finding);
    }

    jdbcClient.sql(START_PROGRESS).param("id", event.reviewId()).update();
    return true;
  }

  private Optional<Long> insertAgentResult(AgentCompletedEvent event) {
    try {
      return jdbcClient
          .sql(INSERT_AGENT_RESULT)
          .param("reviewId", event.reviewId())
          .param("agent", event.agent().name())
          .param("status", event.status().name())
          .param("model", event.usage() == null ? null : event.usage().model())
          .param("inputTokens", event.usage() == null ? null : event.usage().inputTokens())
          .param("outputTokens", event.usage() == null ? null : event.usage().outputTokens())
          .param("latencyMs", event.usage() == null ? null : event.usage().latencyMs())
          .param("errorMessage", event.errorMessage())
          .param("completedAt", OffsetDateTime.now(ZoneOffset.UTC))
          .query(Long.class)
          .optional();
    } catch (DataIntegrityViolationException e) {
      // A única chave estrangeira deste insert é review_id, e o conflito de unicidade já é
      // absorvido pelo ON CONFLICT. Então violação aqui significa review inexistente.
      throw new ReviewNotRegisteredException(event.reviewId());
    }
  }

  private void insertFinding(long agentResultId, Finding finding) {
    jdbcClient
        .sql(INSERT_FINDING)
        .param("agentResultId", agentResultId)
        .param("file", finding.file())
        .param("line", finding.line())
        .param("severity", finding.severity().name())
        .param("title", finding.title())
        .param("explanation", finding.explanation())
        .param("suggestion", finding.suggestion())
        .param("confidence", finding.confidence())
        .update();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ReviewSnapshot> snapshot(UUID reviewId) {
    // As três consultas rodam na mesma transação de leitura: separadas, poderiam ver instantes
    // diferentes e consolidar uma review com agentes de um momento e achados de outro.
    Optional<ReviewRow> review =
        jdbcClient
            .sql(SELECT_REVIEW)
            .param("id", reviewId)
            .query(
                (rs, rowNum) ->
                    new ReviewRow(
                        new PullRequestRef(
                            rs.getLong("installation_id"),
                            rs.getString("owner"),
                            rs.getString("repo"),
                            rs.getInt("pr_number"),
                            rs.getString("head_sha")),
                        rs.getObject("requested_at", OffsetDateTime.class).toInstant()))
            .optional();

    if (review.isEmpty()) {
      return Optional.empty();
    }

    Set<AgentType> responded =
        Set.copyOf(
            jdbcClient
                .sql(SELECT_RESPONDED_AGENTS)
                .param("reviewId", reviewId)
                .query((rs, rowNum) -> AgentType.valueOf(rs.getString("agent")))
                .list());

    List<Finding> findings =
        jdbcClient
            .sql(SELECT_FINDINGS)
            .param("reviewId", reviewId)
            .query(
                (rs, rowNum) ->
                    new Finding(
                        rs.getString("file"),
                        rs.getInt("line"),
                        Severity.valueOf(rs.getString("severity")),
                        rs.getString("title"),
                        rs.getString("explanation"),
                        rs.getString("suggestion"),
                        rs.getDouble("confidence")))
            .list();

    return Optional.of(
        new ReviewSnapshot(
            reviewId, review.get().pullRequest(), responded, findings, review.get().requestedAt()));
  }

  @Override
  public boolean close(
      UUID reviewId, ReviewStatus finalStatus, Verdict verdict, Instant completedAt) {
    int updated =
        jdbcClient
            .sql(CLOSE_REVIEW)
            .param("id", reviewId)
            .param("status", finalStatus.name())
            .param("verdict", verdict == null ? null : verdict.name())
            .param("completedAt", at(completedAt))
            .update();
    return updated == 1;
  }

  @Override
  public List<UUID> findOpenRequestedBefore(Instant cutoff) {
    return jdbcClient.sql(SELECT_OPEN_BEFORE).param("cutoff", at(cutoff)).query(UUID.class).list();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static List<Finding> safe(List<Finding> findings) {
    return findings == null ? List.of() : findings;
  }

  /** Só o que a linha de {@code review} contribui para o snapshot. */
  private record ReviewRow(PullRequestRef pullRequest, Instant requestedAt) {}
}
