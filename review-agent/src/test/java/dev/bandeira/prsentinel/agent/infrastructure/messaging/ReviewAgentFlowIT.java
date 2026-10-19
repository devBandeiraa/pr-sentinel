package dev.bandeira.prsentinel.agent.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.agent.application.port.FindingSource;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent;
import dev.bandeira.prsentinel.common.event.AgentCompletedEvent.Status;
import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent.DiffChunk;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.common.messaging.Topology;
import java.io.IOException;
import java.time.Duration;
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
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O caminho completo do agente, com RabbitMQ de verdade: consome {@code pr.diff.ready} da fila
 * deste tipo e publica {@code pr.agent.completed}.
 *
 * <p>Prova o que teste de unidade não alcança: a fila nomeada por {@code AGENT_TYPE} é declarada e
 * ligada à routing key certa, o {@code @RabbitListener} resolve esse nome por SpEL em tempo de
 * execução, e o limiar de confiança da ADR-004 está de fato no caminho — o fake devolve um achado
 * de 0.9 e um de 0.2, e só um pode sair do agente.
 *
 * <p>O LLM é substituído pela porta: o CLAUDE.md proíbe chamada real em teste, e o único lugar que
 * fala com o modelo de verdade é o harness de evals da fase 7.
 */
@Testcontainers
@SpringBootTest
@Import(ReviewAgentFlowIT.FakeFindingSourceConfiguration.class)
class ReviewAgentFlowIT {

  private static final String TEST_QUEUE = "pr-sentinel.test-agent-completed";

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    // O contexto exige a chave para montar o ChatClient; nenhuma chamada sai daqui, porque a
    // porta FindingSource está substituída.
    registry.add("spring.ai.anthropic.api-key", () -> "chave-de-teste-nao-usada");
    registry.add("agent.type", () -> "SECURITY");
    registry.add("agent.min-confidence", () -> "0.6");
  }

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void prepararFila() {
    rabbitAdmin.initialize();
    rabbitAdmin.purgeQueue(TEST_QUEUE);
  }

  @Test
  void analisaODiffEPublicaOResultadoDoAgente() {
    UUID reviewId = UUID.randomUUID();

    rabbitTemplate.convertAndSend(Topology.EXCHANGE, Topology.RK_DIFF_READY, diffReady(reviewId));

    Message published = awaitMessage();
    AgentCompletedEvent event = convert(published);

    assertThat(event.reviewId()).isEqualTo(reviewId);
    assertThat(event.agent()).isEqualTo(AgentType.SECURITY);
    assertThat(event.status()).isEqualTo(Status.SUCCESS);
    assertThat(event.errorMessage()).isNull();

    assertThat(event.findings())
        .as("o achado de confiança 0.2 não pode sair do agente (ADR-004)")
        .hasSize(1);
    assertThat(event.findings().get(0).confidence()).isEqualTo(0.9);

    String correlationId = published.getMessageProperties().getHeader(Topology.CORRELATION_HEADER);
    assertThat(correlationId).isEqualTo(reviewId.toString());
  }

  private Message awaitMessage() {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> rabbitTemplate.receive(TEST_QUEUE, 500), message -> message != null);
  }

  private AgentCompletedEvent convert(Message message) {
    try {
      return objectMapper.readValue(message.getBody(), AgentCompletedEvent.class);
    } catch (IOException e) {
      throw new IllegalStateException("corpo da mensagem não desserializou", e);
    }
  }

  private static DiffReadyEvent diffReady(UUID reviewId) {
    return new DiffReadyEvent(
        reviewId,
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"),
        List.of(new DiffChunk("src/main/java/Login.java", "java", 0, "@@ -1 +1 @@\n+novo")));
  }

  @TestConfiguration
  static class FakeFindingSourceConfiguration {

    /** Fila no lugar do orchestrator, que não sobe neste contexto. */
    @Bean
    Declarables testQueue() {
      Queue queue = QueueBuilder.durable(TEST_QUEUE).build();
      Binding binding =
          BindingBuilder.bind(queue)
              .to(new TopicExchange(Topology.EXCHANGE))
              .with(Topology.RK_AGENT_COMPLETED);
      return new Declarables(queue, binding);
    }

    @Bean
    @Primary
    FindingSource fakeFindingSource() {
      return chunk -> List.of(finding(chunk.file(), 0.9), finding(chunk.file(), 0.2));
    }

    private static Finding finding(String file, double confidence) {
      return new Finding(
          file,
          11,
          Severity.CRITICAL,
          "SQL injection por concatenação",
          "O nome vem da requisição e entra direto na query.",
          "Use PreparedStatement com parâmetro.",
          confidence);
    }
  }
}
