package dev.bandeira.prsentinel.orchestrator.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.common.messaging.Topology;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O fluxo completo do orchestrator, com Postgres e RabbitMQ reais: registra a review, recebe os
 * três resultados em mensagens independentes e publica um único {@code pr.review.ready}.
 *
 * <p>O timeout é configurado alto de propósito para que a varredura não interfira — o caminho do
 * timeout tem IT próprio, em {@code ReviewTimeoutIT}.
 */
@Testcontainers
@SpringBootTest
@Import(OrchestratorFlowIT.TestQueueConfiguration.class)
class OrchestratorFlowIT {

  private static final String TEST_QUEUE = "pr-sentinel.test-review-ready";
  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(987L, "devBandeiraa", "pr-sentinel", 7, "abc123");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("prsentinel")
          .withUsername("prsentinel")
          .withPassword("prsentinel");

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("orchestrator.agent-timeout", () -> "1h");
    registry.add("orchestrator.timeout-check-interval", () -> "1h");
  }

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void prepararEstado() {
    rabbitAdmin.initialize();
    rabbitAdmin.purgeQueue(TEST_QUEUE);
    jdbcClient.sql("TRUNCATE finding, agent_result, review").update();
  }

  @Test
  void consolidaEPublicaQuandoOsTresAgentesRespondem() {
    UUID reviewId = UUID.randomUUID();

    publishReviewRequested(reviewId);
    awaitReviewRegistered(reviewId);

    publishAgentResult(reviewId, AgentType.SECURITY, finding(Severity.CRITICAL, 0.95));
    publishAgentResult(reviewId, AgentType.PERFORMANCE, finding(Severity.LOW, 0.8));
    publishAgentResult(reviewId, AgentType.STANDARDS, finding(Severity.MEDIUM, 0.7));

    ReviewReadyEvent published = convert(awaitMessage());

    assertThat(published.reviewId()).isEqualTo(reviewId);
    assertThat(published.pullRequest()).isEqualTo(PULL_REQUEST);
    assertThat(published.partial()).isFalse();
    assertThat(published.missingAgents()).isEmpty();
    assertThat(published.verdict()).isEqualTo(Verdict.CHANGES_REQUESTED);
    assertThat(published.durationMs()).isPositive();

    // Os três apontaram Login.java:11 e só o mais severo vai para o PR (ADR-008).
    assertThat(published.findings()).hasSize(1);
    assertThat(published.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);

    assertThat(statusOf(reviewId)).isEqualTo("COMPLETED");
    assertThat(verdictOf(reviewId)).isEqualTo("CHANGES_REQUESTED");

    // Os três achados continuam no banco, mesmo o que foi descartado na consolidação.
    assertThat(countFindings()).isEqualTo(3);
  }

  @Test
  void publicaUmaMensagemSoMesmoComResultadoRepetido() {
    UUID reviewId = UUID.randomUUID();

    publishReviewRequested(reviewId);
    awaitReviewRegistered(reviewId);

    publishAgentResult(reviewId, AgentType.SECURITY);
    publishAgentResult(reviewId, AgentType.PERFORMANCE);
    publishAgentResult(reviewId, AgentType.STANDARDS);
    // A fila entrega ao menos uma vez: o reenvio do resultado de segurança não pode render uma
    // segunda review publicada.
    publishAgentResult(reviewId, AgentType.SECURITY);

    assertThat(awaitMessage()).isNotNull();
    assertThat(rabbitTemplate.receive(TEST_QUEUE, 2_000))
        .as("duas mensagens viram dois comentários no mesmo PR")
        .isNull();
  }

  @Test
  void aEntregaRepetidaDoPedidoNaoCriaDuasReviews() {
    UUID reviewId = UUID.randomUUID();

    publishReviewRequested(reviewId);
    publishReviewRequested(reviewId);
    awaitReviewRegistered(reviewId);

    assertThat(jdbcClient.sql("SELECT COUNT(*) FROM review").query(Integer.class).single())
        .isEqualTo(1);
  }

  private void publishReviewRequested(UUID reviewId) {
    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_REVIEW_REQUESTED,
        new ReviewRequestedEvent(
            reviewId, "delivery-" + reviewId, PULL_REQUEST, Instant.now().minusSeconds(5)));
  }

  private void publishAgentResult(UUID reviewId, AgentType agent, Finding... findings) {
    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_AGENT_COMPLETED,
        new AgentCompletedEvent(reviewId, agent, Status.SUCCESS, List.of(findings), null, null));
  }

  private void awaitReviewRegistered(UUID reviewId) {
    // Publicar resultado antes do registro é corrida real em produção, tratada por retry da fila.
    // Aqui o teste espera, para medir a consolidação e não o retry.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                jdbcClient
                    .sql("SELECT COUNT(*) FROM review WHERE id = :id")
                    .param("id", reviewId)
                    .query(Integer.class)
                    .single(),
            count -> count == 1);
  }

  private Message awaitMessage() {
    return await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> rabbitTemplate.receive(TEST_QUEUE, 500), message -> message != null);
  }

  private ReviewReadyEvent convert(Message message) {
    try {
      return objectMapper.readValue(message.getBody(), ReviewReadyEvent.class);
    } catch (IOException e) {
      throw new IllegalStateException("corpo da mensagem não desserializou", e);
    }
  }

  private String statusOf(UUID reviewId) {
    return jdbcClient
        .sql("SELECT status FROM review WHERE id = :id")
        .param("id", reviewId)
        .query(String.class)
        .single();
  }

  private String verdictOf(UUID reviewId) {
    return jdbcClient
        .sql("SELECT verdict FROM review WHERE id = :id")
        .param("id", reviewId)
        .query(String.class)
        .single();
  }

  private int countFindings() {
    return jdbcClient.sql("SELECT COUNT(*) FROM finding").query(Integer.class).single();
  }

  private static Finding finding(Severity severity, double confidence) {
    return new Finding(
        "src/main/java/Login.java",
        11,
        severity,
        "achado de " + severity,
        "explicação do problema",
        "sugestão de correção",
        confidence);
  }

  @TestConfiguration
  static class TestQueueConfiguration {

    /** Fila no lugar do github-publisher, que não sobe neste contexto. */
    @Bean
    Declarables testQueue() {
      Queue queue = QueueBuilder.durable(TEST_QUEUE).build();
      Binding binding =
          BindingBuilder.bind(queue)
              .to(new TopicExchange(Topology.EXCHANGE))
              .with(Topology.RK_REVIEW_READY);
      return new Declarables(queue, binding);
    }
  }
}
