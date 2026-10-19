package dev.bandeira.prsentinel.gateway.infrastructure.persistence;

import dev.bandeira.prsentinel.gateway.application.port.ProcessedDeliveries;
import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Registro de entregas processadas no Postgres.
 *
 * <p>A trava de idempotência é a chave primária de {@code webhook_delivery}, não uma consulta
 * prévia: o {@code ON CONFLICT DO NOTHING} deixa o Postgres decidir quem reservou. Com duas
 * entregas simultâneas, o segundo INSERT espera no índice único até o primeiro commitar e então
 * afeta zero linhas — exatamente uma publicação (ADR-005).
 */
@Repository
public class JdbcProcessedDeliveries implements ProcessedDeliveries {

  // A tabela é qualificada com o schema porque o search_path da conexão continua sendo o padrão
  // ("$user", public): quem manda no schema é o Flyway (default-schema: gateway), não a conexão.
  // Sem a qualificação o INSERT procuraria webhook_delivery no public e falharia.
  private static final String RESERVE_SQL =
      """
      INSERT INTO gateway.webhook_delivery (delivery_id, review_id, action, received_at)
      VALUES (:deliveryId, :reviewId, :action, :receivedAt)
      ON CONFLICT (delivery_id) DO NOTHING
      """;

  private static final String PURGE_SQL =
      "DELETE FROM gateway.webhook_delivery WHERE received_at < :cutoff";

  private final JdbcClient jdbcClient;

  public JdbcProcessedDeliveries(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public boolean register(DeliveryId id, UUID reviewId, PullRequestAction action) {
    int inserted =
        jdbcClient
            .sql(RESERVE_SQL)
            .param("deliveryId", id.value())
            .param("reviewId", reviewId)
            .param("action", action.name())
            .param("receivedAt", OffsetDateTime.now(ZoneOffset.UTC))
            .update();
    return inserted == 1;
  }

  @Override
  public int purgeOlderThan(Instant cutoff) {
    return jdbcClient.sql(PURGE_SQL).param("cutoff", cutoff.atOffset(ZoneOffset.UTC)).update();
  }
}
