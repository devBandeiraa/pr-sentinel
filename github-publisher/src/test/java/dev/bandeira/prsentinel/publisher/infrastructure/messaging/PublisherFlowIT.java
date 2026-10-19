package dev.bandeira.prsentinel.publisher.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.bandeira.prsentinel.common.event.AgentType;
import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.Severity;
import dev.bandeira.prsentinel.common.messaging.Topology;
import dev.bandeira.prsentinel.publisher.application.port.PullRequestReviews;
import dev.bandeira.prsentinel.publisher.domain.GithubReviewEvent;
import dev.bandeira.prsentinel.publisher.domain.RenderedReview;
import dev.bandeira.prsentinel.publisher.domain.ReviewMarker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
 * O caminho do serviço com RabbitMQ real: consome {@code pr.review.ready} e manda a review para a
 * porta de publicação.
 *
 * <p>Prova o que teste de unidade não alcança: a fila é declarada e ligada à routing key certa, o
 * listener está escutando, e a chave privada do App é lida no boot — gerada aqui em PKCS#8 num
 * arquivo temporário, o mesmo caminho de configuração da ADR-006.
 *
 * <p>A porta {@link PullRequestReviews} é substituída: escrever num PR de verdade em teste está
 * fora de questão.
 */
@Testcontainers
@SpringBootTest
@Import(PublisherFlowIT.RecordingReviewsConfiguration.class)
class PublisherFlowIT {

  private static final PullRequestRef PULL_REQUEST =
      new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("github.app-id", () -> "123456");
    registry.add("github.private-key-path", PublisherFlowIT::generatedPrivateKey);
  }

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RecordingPullRequestReviews reviews;

  @Test
  void publicaAReviewNoPrQuandoOEventoChega() {
    UUID reviewId = UUID.randomUUID();
    reviews.clear();

    rabbitTemplate.convertAndSend(Topology.EXCHANGE, Topology.RK_REVIEW_READY, event(reviewId));

    await().atMost(Duration.ofSeconds(10)).until(() -> !reviews.published().isEmpty());

    RecordingPullRequestReviews.Published published = reviews.published().get(0);
    assertThat(published.pullRequest()).isEqualTo(PULL_REQUEST);
    assertThat(published.event())
        .as("o padrão não bloqueia merge (ADR-009)")
        .isEqualTo(GithubReviewEvent.COMMENT);
    assertThat(published.review().summary())
        .contains("PR Sentinel")
        .contains("Review parcial")
        .contains("STANDARDS")
        .contains(ReviewMarker.of(reviewId));
    assertThat(published.review().comments()).hasSize(1);
    assertThat(published.review().comments().get(0).path()).isEqualTo("src/main/java/Login.java");
  }

  private static ReviewReadyEvent event(UUID reviewId) {
    return new ReviewReadyEvent(
        reviewId,
        PULL_REQUEST,
        Verdict.CHANGES_REQUESTED,
        true,
        List.of(AgentType.STANDARDS),
        List.of(
            new Finding(
                "src/main/java/Login.java",
                11,
                Severity.CRITICAL,
                "SQL injection por concatenação",
                "O nome vem da requisição e entra direto na query.",
                "Use PreparedStatement com parâmetro.",
                0.95)),
        42_300L);
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
  static class RecordingReviewsConfiguration {

    @Bean
    @Primary
    RecordingPullRequestReviews recordingPullRequestReviews() {
      return new RecordingPullRequestReviews();
    }
  }

  /** Guarda o que seria postado no PR, em vez de postar. */
  static class RecordingPullRequestReviews implements PullRequestReviews {

    private final List<Published> published = new ArrayList<>();

    record Published(PullRequestRef pullRequest, RenderedReview review, GithubReviewEvent event) {}

    List<Published> published() {
      return List.copyOf(published);
    }

    void clear() {
      published.clear();
    }

    @Override
    public boolean alreadyPublished(PullRequestRef pullRequest, UUID reviewId) {
      return false;
    }

    @Override
    public void publish(
        PullRequestRef pullRequest, RenderedReview review, GithubReviewEvent event) {
      published.add(new Published(pullRequest, review, event));
    }
  }
}
