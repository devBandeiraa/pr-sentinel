package dev.bandeira.prsentinel.orchestrator.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.orchestrator.application.ReviewNotRegisteredException;
import dev.bandeira.prsentinel.orchestrator.application.ReviewSnapshot;
import dev.bandeira.prsentinel.orchestrator.domain.ReviewStatus;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O SQL do orchestrator contra Postgres de verdade.
 *
 * <p>Três coisas aqui não têm como ser testadas sem banco: o {@code ON CONFLICT DO NOTHING} que
 * torna o registro idempotente, a unicidade {@code review_id + agent} que descarta resultado
 * repetido, e o {@code UPDATE} condicional por status que garante publicação única (ADR-008). O
 * último tem teste com duas threads, porque é exatamente sob concorrência que um {@code SELECT}
 * seguido de {@code UPDATE} passaria no teste sequencial e publicaria duas vezes em produção.
 */
@Testcontainers
class JdbcReviewsIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("prsentinel")
          .withUsername("prsentinel")
          .withPassword("prsentinel");

  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(987L, "devBandeiraa", "pr-sentinel", 7, "abc123");
  private static final Instant REQUESTED_AT = Instant.parse("2026-10-19T12:00:00Z");

  private static HikariDataSource dataSource;
  private static JdbcReviews reviews;

  @BeforeAll
  static void migrateAndBuildRepository() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(4);
    dataSource = new HikariDataSource(config);

    // Sem schema próprio, ao contrário do gateway: o histórico do Flyway deste serviço fica no
    // public, e é o do gateway que foi movido (ADR-005).
    Flyway.configure().dataSource(dataSource).load().migrate();

    reviews = new JdbcReviews(JdbcClient.create(dataSource));
  }

  @AfterAll
  static void closeDataSource() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @AfterEach
  void limpaTabelas() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("TRUNCATE finding, agent_result, review");
    }
  }

  @Test
  void aMigrationV2AcrescentouOInstallationId() throws Exception {
    // Sem essa coluna não dá para reconstruir o PullRequestRef, e o github-publisher da fase 6
    // não teria como trocar o id pelo token do App.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        var rs =
            statement.executeQuery(
                "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'review' AND column_name = 'installation_id'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("is_nullable")).isEqualTo("NO");
    }
  }

  @Test
  void registraAReviewUmaVezSo() {
    UUID reviewId = UUID.randomUUID();

    assertThat(reviews.register(request(reviewId, "delivery-1"))).isTrue();
    assertThat(reviews.register(request(reviewId, "delivery-1"))).isFalse();

    assertThat(reviews.findOpenRequestedBefore(REQUESTED_AT.plusSeconds(1)))
        .containsExactly(reviewId);
  }

  @Test
  void snapshotReconstituiOPullRequestInteiro() {
    UUID reviewId = UUID.randomUUID();
    reviews.register(request(reviewId, "delivery-1"));

    ReviewSnapshot snapshot = reviews.snapshot(reviewId).orElseThrow();

    assertThat(snapshot.pullRequest()).isEqualTo(PULL_REQUEST);
    assertThat(snapshot.requestedAt()).isEqualTo(REQUESTED_AT);
    assertThat(snapshot.respondedAgents()).isEmpty();
    assertThat(snapshot.findings()).isEmpty();
  }

  @Test
  void snapshotDeReviewInexistenteVemVazio() {
    assertThat(reviews.snapshot(UUID.randomUUID())).isEmpty();
  }

  @Test
  void gravaOResultadoDoAgenteComOsAchados() {
    UUID reviewId = UUID.randomUUID();
    reviews.register(request(reviewId, "delivery-1"));

    assertThat(
            reviews.recordAgentResult(
                result(reviewId, AgentType.SECURITY, finding(Severity.CRITICAL, 0.95))))
        .isTrue();

    ReviewSnapshot snapshot = reviews.snapshot(reviewId).orElseThrow();
    assertThat(snapshot.respondedAgents()).containsExactly(AgentType.SECURITY);
    assertThat(snapshot.findings()).hasSize(1);

    Finding stored = snapshot.findings().get(0);
    assertThat(stored.file()).isEqualTo("src/main/java/Login.java");
    assertThat(stored.line()).isEqualTo(11);
    assertThat(stored.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(stored.confidence()).isEqualTo(0.95);
  }

  @Test
  void oMesmoAgenteNaoGravaDuasVezesNaMesmaReview() {
    UUID reviewId = UUID.randomUUID();
    reviews.register(request(reviewId, "delivery-1"));
    AgentCompletedEvent event = result(reviewId, AgentType.SECURITY, finding(Severity.HIGH, 0.9));

    assertThat(reviews.recordAgentResult(event)).isTrue();
    assertThat(reviews.recordAgentResult(event)).isFalse();

    assertThat(reviews.snapshot(reviewId).orElseThrow().findings())
        .as("o achado não pode ser gravado de novo junto com o resultado repetido")
        .hasSize(1);
  }

  @Test
  void resultadoDeReviewInexistenteFalhaComoReviewNaoRegistrada() {
    AgentCompletedEvent orfao =
        result(UUID.randomUUID(), AgentType.SECURITY, finding(Severity.LOW, 0.7));

    assertThatThrownBy(() -> reviews.recordAgentResult(orfao))
        .isInstanceOf(ReviewNotRegisteredException.class);
  }

  @Test
  void oPrimeiroResultadoLevaAReviewParaEmAndamento() {
    UUID reviewId = UUID.randomUUID();
    reviews.register(request(reviewId, "delivery-1"));
    reviews.recordAgentResult(result(reviewId, AgentType.SECURITY));

    assertThat(statusOf(reviewId)).isEqualTo(ReviewStatus.IN_PROGRESS.name());
  }

  @Test
  void encerrarFuncionaUmaVezESomenteUma() {
    UUID reviewId = UUID.randomUUID();
    reviews.register(request(reviewId, "delivery-1"));

    assertThat(reviews.close(reviewId, ReviewStatus.COMPLETED, Verdict.APPROVED, Instant.now()))
        .isTrue();
    assertThat(reviews.close(reviewId, ReviewStatus.PARTIAL, Verdict.ATTENTION, Instant.now()))
        .as("review encerrada não pode ser encerrada de novo, nem com outro status")
        .isFalse();

    assertThat(statusOf(reviewId)).isEqualTo(ReviewStatus.COMPLETED.name());
  }

  @Test
  void duasThreadsEncerrandoAMesmaReviewProduzemApenasUmTrue() throws Exception {
    // É aqui que mora a diferença entre um comentário e dois no mesmo PR: o terceiro agente e a
    // varredura de timeout podem chegar juntos.
    UUID reviewId = UUID.randomUUID();
    reviews.register(request(reviewId, "delivery-1"));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      List<Future<Boolean>> futures =
          List.of(
              executor.submit(closeTask(reviewId, ReviewStatus.COMPLETED, ready, start)),
              executor.submit(closeTask(reviewId, ReviewStatus.PARTIAL, ready, start)));

      ready.await(5, TimeUnit.SECONDS);
      start.countDown();

      long trueCount = 0;
      for (Future<Boolean> future : futures) {
        if (future.get(5, TimeUnit.SECONDS)) {
          trueCount++;
        }
      }

      assertThat(trueCount).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void aVarreduraVeSoAsReviewsAbertasEAnterioresAoCutoff() {
    UUID antiga = UUID.randomUUID();
    UUID recente = UUID.randomUUID();
    UUID encerrada = UUID.randomUUID();

    reviews.register(
        new ReviewRequestedEvent(
            antiga, "delivery-antiga", PULL_REQUEST, REQUESTED_AT.minusSeconds(600)));
    reviews.register(
        new ReviewRequestedEvent(
            recente, "delivery-recente", PULL_REQUEST, REQUESTED_AT.plusSeconds(600)));
    reviews.register(
        new ReviewRequestedEvent(
            encerrada, "delivery-encerrada", PULL_REQUEST, REQUESTED_AT.minusSeconds(600)));
    reviews.close(encerrada, ReviewStatus.COMPLETED, Verdict.APPROVED, Instant.now());

    assertThat(reviews.findOpenRequestedBefore(REQUESTED_AT)).containsExactly(antiga);
  }

  @Test
  void aVarreduraDevolveAsMaisAntigasPrimeiro() {
    UUID maisAntiga = UUID.randomUUID();
    UUID menosAntiga = UUID.randomUUID();
    reviews.register(
        new ReviewRequestedEvent(
            menosAntiga, "delivery-2", PULL_REQUEST, REQUESTED_AT.minusSeconds(60)));
    reviews.register(
        new ReviewRequestedEvent(
            maisAntiga, "delivery-1", PULL_REQUEST, REQUESTED_AT.minusSeconds(600)));

    assertThat(reviews.findOpenRequestedBefore(REQUESTED_AT))
        .containsExactly(maisAntiga, menosAntiga);
  }

  private Callable<Boolean> closeTask(
      UUID reviewId, ReviewStatus status, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      return reviews.close(reviewId, status, Verdict.APPROVED, Instant.now());
    };
  }

  private String statusOf(UUID reviewId) {
    return JdbcClient.create(dataSource)
        .sql("SELECT status FROM review WHERE id = :id")
        .param("id", reviewId)
        .query(String.class)
        .single();
  }

  private static ReviewRequestedEvent request(UUID reviewId, String deliveryId) {
    return new ReviewRequestedEvent(reviewId, deliveryId, PULL_REQUEST, REQUESTED_AT);
  }

  private static AgentCompletedEvent result(UUID reviewId, AgentType agent, Finding... findings) {
    return new AgentCompletedEvent(reviewId, agent, Status.SUCCESS, List.of(findings), null, null);
  }

  private static Finding finding(Severity severity, double confidence) {
    return new Finding(
        "src/main/java/Login.java",
        11,
        severity,
        "SQL injection por concatenação",
        "O nome vem da requisição e entra direto na query.",
        "Use PreparedStatement com parâmetro.",
        confidence);
  }
}
