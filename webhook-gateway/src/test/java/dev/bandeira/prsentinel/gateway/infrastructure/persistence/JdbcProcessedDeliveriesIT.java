package dev.bandeira.prsentinel.gateway.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Testes de integração com Testcontainers Postgres (ADR-005).
 *
 * <p>O container é estático e reusado por toda a classe — subir Postgres é caro e não há
 * necessidade de isolamento entre métodos além da limpeza da tabela em {@link #limpaTabela()}.
 *
 * <p>Migração e {@link JdbcProcessedDeliveries} são montadas manualmente com Flyway e {@link
 * HikariDataSource} (em vez de {@code @SpringBootTest}) porque nada aqui depende do contexto
 * Spring: só precisamos do schema aplicado e da classe de produção sob teste.
 */
@Testcontainers
class JdbcProcessedDeliveriesIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("prsentinel")
          .withUsername("prsentinel")
          .withPassword("prsentinel");

  private static HikariDataSource dataSource;
  private static JdbcProcessedDeliveries repository;

  @BeforeAll
  static void migrateSchemaAndCreateRepository() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(4);
    // A migration cria a tabela sem qualificar o schema (CREATE TABLE webhook_delivery), contando
    // com o search_path da conexão — o mesmo vale para o SQL de produção. Sem isso aqui, SQL não
    // qualificado resolveria para public, não gateway, e o teste quebraria por erro de SQL em vez
    // de por asserção.
    config.setConnectionInitSql("SET search_path TO gateway, public");
    dataSource = new HikariDataSource(config);

    // Mesma configuração do application.yml: schema próprio do gateway, para que o histórico
    // do Flyway não colida com o do orchestrator, que vive no public (ADR-005).
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("gateway")
        .defaultSchema("gateway")
        .createSchemas(true)
        .load()
        .migrate();

    repository = new JdbcProcessedDeliveries(JdbcClient.create(dataSource));
  }

  @AfterAll
  static void closeDataSource() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @AfterEach
  void limpaTabela() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM gateway.webhook_delivery");
    }
  }

  @Test
  void migrationCriaTabelaWebhookDeliveryNoSchemaGateway() throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                    + "WHERE table_schema = 'gateway' AND table_name = 'webhook_delivery'");
        ResultSet resultSet = statement.executeQuery()) {
      assertThat(resultSet.next()).isTrue();
    }
  }

  @Test
  void historicoDoFlywayFicaNoSchemaGatewayENaoNoPublic() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      try (PreparedStatement inGateway =
              connection.prepareStatement(
                  "SELECT 1 FROM information_schema.tables "
                      + "WHERE table_schema = 'gateway' AND table_name = 'flyway_schema_history'");
          ResultSet gatewayResult = inGateway.executeQuery()) {
        assertThat(gatewayResult.next())
            .as("flyway_schema_history deve existir no schema gateway")
            .isTrue();
      }

      try (PreparedStatement inPublic =
              connection.prepareStatement(
                  "SELECT 1 FROM information_schema.tables "
                      + "WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'");
          ResultSet publicResult = inPublic.executeQuery()) {
        assertThat(publicResult.next())
            .as(
                "flyway_schema_history não pode existir no public, para não colidir com o"
                    + " histórico do orchestrator")
            .isFalse();
      }
    }
  }

  @Test
  void registraIdNovoComSucesso() throws Exception {
    DeliveryId deliveryId = new DeliveryId("delivery-novo");
    UUID reviewId = UUID.randomUUID();

    boolean reserved = repository.register(deliveryId, reviewId, PullRequestAction.OPENED);

    assertThat(reserved).isTrue();
    assertThat(countRows()).isEqualTo(1);

    // Não basta existir uma linha: precisa ser a linha com os valores que foram passados —
    // um bind trocado no JdbcClient (ex.: action no lugar de review_id) ainda passaria só no
    // count.
    StoredDelivery stored = fetchDelivery("delivery-novo");
    assertThat(stored.reviewId()).isEqualTo(reviewId);
    // A convenção de como PullRequestAction vira texto no banco (nome do enum vs. string crua do
    // GitHub) não está fixada por nenhum ADR — comparamos contra o próprio enum para flagar um bind
    // trocado (review_id/action) sem travar numa formatação específica que não é nossa de definir.
    assertThat(stored.action()).isEqualTo(PullRequestAction.OPENED.name());
  }

  @Test
  void naoRegistraNovamenteUmIdJaProcessado() throws Exception {
    DeliveryId deliveryId = new DeliveryId("delivery-repetido");

    boolean first = repository.register(deliveryId, UUID.randomUUID(), PullRequestAction.OPENED);
    boolean second = repository.register(deliveryId, UUID.randomUUID(), PullRequestAction.OPENED);

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(countRows()).isEqualTo(1);
  }

  @Test
  void duasThreadsRegistrandoOMesmoIdSimultaneamenteProduzemApenasUmTrue() throws Exception {
    DeliveryId deliveryId = new DeliveryId("delivery-concorrente");
    int threadCount = 2;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    try {
      List<Future<Boolean>> futures =
          List.of(
              executor.submit(registerTask(deliveryId, ready, start)),
              executor.submit(registerTask(deliveryId, ready, start)));

      ready.await(5, TimeUnit.SECONDS);
      start.countDown();

      long trueCount = 0;
      for (Future<Boolean> future : futures) {
        if (future.get(5, TimeUnit.SECONDS)) {
          trueCount++;
        }
      }

      assertThat(trueCount).isEqualTo(1);
      assertThat(countRows()).isEqualTo(1);
    } finally {
      executor.shutdown();
    }
  }

  private Callable<Boolean> registerTask(
      DeliveryId deliveryId, CountDownLatch ready, CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      return repository.register(deliveryId, UUID.randomUUID(), PullRequestAction.OPENED);
    };
  }

  @Test
  void purgeOlderThanRemoveApenasRegistrosAnterioresAoCutoffEDevolveAContagem() throws Exception {
    Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
    insertRowWithReceivedAt("delivery-antigo", cutoff.minus(1, ChronoUnit.DAYS));
    insertRowWithReceivedAt("delivery-recente", cutoff.plus(1, ChronoUnit.DAYS));

    int purged = repository.purgeOlderThan(cutoff);

    assertThat(purged).isEqualTo(1);
    assertThat(countRows()).isEqualTo(1);
    assertThat(existsDelivery("delivery-recente")).isTrue();
    assertThat(existsDelivery("delivery-antigo")).isFalse();
  }

  private void insertRowWithReceivedAt(String deliveryId, Instant receivedAt) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO gateway.webhook_delivery (delivery_id, review_id, action,"
                    + " received_at) VALUES (?, ?, ?, ?)")) {
      statement.setString(1, deliveryId);
      statement.setObject(2, UUID.randomUUID());
      statement.setString(3, PullRequestAction.OPENED.name());
      statement.setObject(4, receivedAt.atOffset(ZoneOffset.UTC));
      statement.executeUpdate();
    }
  }

  private boolean existsDelivery(String deliveryId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT 1 FROM gateway.webhook_delivery WHERE delivery_id = ?")) {
      statement.setString(1, deliveryId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private int countRows() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("SELECT COUNT(*) FROM gateway.webhook_delivery")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private StoredDelivery fetchDelivery(String deliveryId) throws Exception {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT review_id, action FROM gateway.webhook_delivery WHERE delivery_id = ?")) {
      statement.setString(1, deliveryId);
      try (ResultSet resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).as("linha para %s deveria existir", deliveryId).isTrue();
        UUID reviewId = (UUID) resultSet.getObject("review_id");
        String action = resultSet.getString("action");
        return new StoredDelivery(reviewId, action);
      }
    }
  }

  private record StoredDelivery(UUID reviewId, String action) {}
}
