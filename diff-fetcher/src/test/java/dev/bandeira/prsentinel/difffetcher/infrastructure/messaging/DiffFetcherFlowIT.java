package dev.bandeira.prsentinel.difffetcher.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.common.event.DiffReadyEvent;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.difffetcher.application.port.PullRequestFiles;
import dev.bandeira.prsentinel.difffetcher.domain.ChangedFile;
import dev.bandeira.prsentinel.difffetcher.domain.FileStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
 * O caminho completo dentro do serviço: consome {@code pr.review.requested} e publica {@code
 * pr.diff.ready}, com RabbitMQ de verdade.
 *
 * <p>Prova três coisas que teste de unidade não alcança: a fila do serviço é declarada e ligada à
 * routing key certa, o listener está mesmo escutando nela, e a chave privada do App é lida no boot
 * — a chave é gerada aqui em PKCS#8 e escrita num arquivo temporário, que é o mesmo caminho de
 * configuração da ADR-006.
 *
 * <p>A porta {@link PullRequestFiles} é substituída: o que está sob teste é a mensageria, e chamar
 * a API do GitHub num teste está proibido pelo CLAUDE.md.
 */
@Testcontainers
@SpringBootTest
@Import(DiffFetcherFlowIT.FakeFilesConfiguration.class)
class DiffFetcherFlowIT {

  private static final String TEST_QUEUE = "pr-sentinel.test-diff-ready";

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("github.app-id", () -> "123456");
    registry.add("github.private-key-path", DiffFetcherFlowIT::generatedPrivateKey);
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
  void publicaOsChunksDoDiffQuandoChegaUmPedidoDeReview() {
    UUID reviewId = UUID.randomUUID();

    rabbitTemplate.convertAndSend(
        Topology.EXCHANGE, Topology.RK_REVIEW_REQUESTED, reviewRequested(reviewId));

    Message published = awaitMessage();
    DiffReadyEvent event = convert(published);

    assertThat(event.reviewId()).isEqualTo(reviewId);
    assertThat(event.pullRequest().fullName()).isEqualTo("devBandeiraa/pr-sentinel#7");
    // Dos três arquivos do fake, só o .java é revisável: o lockfile casa com um padrão ignorado
    // e o .png vem sem patch.
    assertThat(event.chunks()).hasSize(1);
    assertThat(event.chunks().get(0).file()).isEqualTo("src/main/java/App.java");
    assertThat(event.chunks().get(0).language()).isEqualTo("java");

    String correlationId = published.getMessageProperties().getHeader(Topology.CORRELATION_HEADER);
    assertThat(correlationId).isEqualTo(reviewId.toString());
  }

  private Message awaitMessage() {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> rabbitTemplate.receive(TEST_QUEUE, 500), message -> message != null);
  }

  private DiffReadyEvent convert(Message message) {
    try {
      return objectMapper.readValue(message.getBody(), DiffReadyEvent.class);
    } catch (IOException e) {
      throw new IllegalStateException("corpo da mensagem não desserializou", e);
    }
  }

  private static ReviewRequestedEvent reviewRequested(UUID reviewId) {
    return new ReviewRequestedEvent(
        reviewId,
        "delivery-1",
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"),
        Instant.parse("2026-10-19T12:00:00Z"));
  }

  /** Gera um par RSA e grava a chave privada em PKCS#8, como o serviço espera encontrar. */
  private static String generatedPrivateKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();

      Path file = Files.createTempFile("github-app-", ".pem");
      Files.writeString(
          file,
          "-----BEGIN PRIVATE KEY-----\n"
              + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
              + "\n-----END PRIVATE KEY-----\n");
      file.toFile().deleteOnExit();
      return file.toString();
    } catch (Exception e) {
      throw new IllegalStateException("não foi possível gerar a chave de teste", e);
    }
  }

  @TestConfiguration
  static class FakeFilesConfiguration {

    /** Fila no lugar dos três agentes, que não sobem neste contexto. */
    @Bean
    Declarables testQueue() {
      Queue queue = QueueBuilder.durable(TEST_QUEUE).build();
      Binding binding =
          BindingBuilder.bind(queue)
              .to(new TopicExchange(Topology.EXCHANGE))
              .with(Topology.RK_DIFF_READY);
      return new Declarables(queue, binding);
    }

    @Bean
    @Primary
    PullRequestFiles fakePullRequestFiles() {
      return pullRequest ->
          List.of(
              new ChangedFile(
                  "src/main/java/App.java", FileStatus.MODIFIED, "@@ -1,2 +1,3 @@\n ctx\n+novo"),
              new ChangedFile("package-lock.json", FileStatus.MODIFIED, "@@ -1 +1 @@\n+ruido"),
              new ChangedFile("docs/logo.png", FileStatus.ADDED, null));
    }
  }
}
