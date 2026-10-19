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
 * O caminho do timeout, com o agendador real (ADR-003).
 *
 * <p>Sem isto, um agente fora do ar deixa a review presa em {@code IN_PROGRESS} para sempre e o
 * autor do PR espera um retorno que nunca chega. O teste de unidade prova a regra; aqui se prova
 * que o {@code @Scheduled} está ligado, que o prazo vem da configuração e que a varredura enxerga
 * as reviews gravadas no banco.
 *
 * <p>Contexto próprio porque os prazos são de segundos: no {@code OrchestratorFlowIT} a varredura
 * precisa justamente não interferir.
 */
@Testcontainers
@SpringBootTest
@Import(ReviewTimeoutIT.TestQueueConfiguration.class)
class ReviewTimeoutIT {

  private static final String TEST_QUEUE = "pr-sentinel.test-review-ready-timeout";
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
    registry.add("orchestrator.agent-timeout", () -> "1s");
    registry.add("orchestrator.timeout-check-interval", () -> "1s");
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
  void publicaParcialComOAgenteQueRespondeu() {
    UUID reviewId = UUID.randomUUID();

    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_REVIEW_REQUESTED,
        new ReviewRequestedEvent(
            reviewId, "delivery-" + reviewId, PULL_REQUEST, Instant.now().minusSeconds(30)));

    awaitReviewRegistered(reviewId);

    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_AGENT_COMPLETED,
        new AgentCompletedEvent(
            reviewId,
            AgentType.SECURITY,
            Status.SUCCESS,
            List.of(finding(Severity.HIGH)),
            null,
            null));

    ReviewReadyEvent published = convert(awaitMessage());

    assertThat(published.reviewId()).isEqualTo(reviewId);
    assertThat(published.partial()).isTrue();
    assertThat(published.missingAgents())
        .containsExactly(AgentType.PERFORMANCE, AgentType.STANDARDS);
    assertThat(published.verdict()).isEqualTo(Verdict.CHANGES_REQUESTED);
    assertThat(published.findings()).hasSize(1);

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> statusOf(reviewId), status -> status.equals("PARTIAL"));
  }

  @Test
  void reviewSemNenhumaRespostaTerminaEmFailedMasAindaEhPublicada() {
    UUID reviewId = UUID.randomUUID();

    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_REVIEW_REQUESTED,
        new ReviewRequestedEvent(
            reviewId, "delivery-" + reviewId, PULL_REQUEST, Instant.now().minusSeconds(30)));

    ReviewReadyEvent published = convert(awaitMessage());

    assertThat(published.reviewId()).isEqualTo(reviewId);
    assertThat(published.partial()).isTrue();
    assertThat(published.missingAgents()).containsExactlyInAnyOrder(AgentType.values());
    assertThat(published.findings()).isEmpty();

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> statusOf(reviewId), status -> status.equals("FAILED"));
  }

  @Test
  void aVarreduraNaoRepublicaAMesmaReview() {
    UUID reviewId = UUID.randomUUID();

    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE,
        Topology.RK_REVIEW_REQUESTED,
        new ReviewRequestedEvent(
            reviewId, "delivery-" + reviewId, PULL_REQUEST, Instant.now().minusSeconds(30)));

    assertThat(awaitMessage()).isNotNull();

    // A varredura roda a cada segundo; nenhuma das passadas seguintes pode publicar de novo.
    assertThat(rabbitTemplate.receive(TEST_QUEUE, 4_000))
        .as("a transição condicional da ADR-008 impede a republicação")
        .isNull();
  }

  private void awaitReviewRegistered(UUID reviewId) {
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
        .atMost(Duration.ofSeconds(20))
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

  private static Finding finding(Severity severity) {
    return new Finding(
        "src/main/java/Login.java",
        11,
        severity,
        "achado de " + severity,
        "explicação do problema",
        "sugestão de correção",
        0.9);
  }

  @TestConfiguration
  static class TestQueueConfiguration {

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
