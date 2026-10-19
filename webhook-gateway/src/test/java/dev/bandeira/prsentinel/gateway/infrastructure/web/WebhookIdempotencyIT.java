package dev.bandeira.prsentinel.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Idempotência ponta a ponta: HTTP, Postgres e RabbitMQ reais (ADR-005).
 *
 * <p>Os testes de unidade provam a decisão do caso de uso e o IT de persistência prova a trava do
 * banco. O que só aparece aqui é o resultado que interessa de verdade ao sistema: duas entregas do
 * mesmo {@code X-GitHub-Delivery} deixam <b>uma</b> mensagem no exchange. É esta mensagem que faz
 * fan-out para o diff-fetcher e custa três chamadas de LLM.
 *
 * <p>A fila de teste é declarada aqui porque o gateway só publica — quem declara fila ligada a
 * {@code pr.review.requested} são os consumidores, que não sobem neste contexto.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WebhookIdempotencyIT.TestQueueConfiguration.class)
class WebhookIdempotencyIT {

  private static final String SECRET = "segredo-de-teste";
  private static final String TEST_QUEUE = "pr-sentinel.test-review-requested";

  private static final String PAYLOAD =
      """
      {
        "action": "opened",
        "installation": { "id": 42 },
        "repository": { "name": "pr-sentinel", "owner": { "login": "devBandeiraa" } },
        "pull_request": { "number": 7, "head": { "sha": "abc123" } }
      }
      """;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("prsentinel")
          .withUsername("prsentinel")
          .withPassword("prsentinel");

  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    registry.add("github.webhook.secret", () -> SECRET);
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void limpaEstado() {
    // initialize() força a declaração da fila de teste antes do primeiro publish; sem isso a
    // primeira mensagem poderia ser publicada num exchange que ainda não tem binding e sumir.
    rabbitAdmin.initialize();
    rabbitAdmin.purgeQueue(TEST_QUEUE);
    jdbcClient.sql("DELETE FROM gateway.webhook_delivery").update();
  }

  @Test
  void aMesmaEntregaDuasVezesPublicaUmaUnicaMensagem() {
    String deliveryId = "72d3162e-cc78-11e3-81ab-4c9367dc0958";

    assertThat(post(deliveryId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(post(deliveryId).getStatusCode())
        .as("a duplicata também responde 202: não-2xx faria o GitHub marcar falha permanente")
        .isEqualTo(HttpStatus.ACCEPTED);

    Message first = receive();
    assertThat(first).as("a primeira entrega tem de ter publicado").isNotNull();
    assertThat(receive()).as("a reentrega não pode ter publicado nada").isNull();

    ReviewRequestedEvent event = (ReviewRequestedEvent) convert(first);
    assertThat(event.deliveryId()).isEqualTo(deliveryId);
    assertThat(event.pullRequest().fullName()).isEqualTo("devBandeiraa/pr-sentinel#7");
    String correlationId = first.getMessageProperties().getHeader(Topology.CORRELATION_HEADER);
    assertThat(correlationId).isEqualTo(event.reviewId().toString());

    assertThat(countRows()).isEqualTo(1);
  }

  @Test
  void entregasDiferentesDoMesmoPrPublicamUmaMensagemCadaUma() {
    // Um push novo no PR gera outro delivery id, e aí review nova é o comportamento correto:
    // a idempotência é por entrega, não por pull request.
    assertThat(post("delivery-primeira").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(post("delivery-segunda").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(receive()).isNotNull();
    assertThat(receive()).isNotNull();
    assertThat(receive()).isNull();

    assertThat(countRows()).isEqualTo(2);
  }

  @Test
  void acaoQueNaoDisparaReviewNaoPublicaNemRegistra() {
    String payload = PAYLOAD.replace("\"opened\"", "\"closed\"");

    assertThat(post("delivery-closed", payload).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    assertThat(receive()).isNull();
    assertThat(countRows()).as("ação ignorada não gasta linha na tabela de idempotência").isZero();
  }

  @Test
  void assinaturaInvalidaResponde401ENaoPublica() {
    HttpHeaders headers = headers("delivery-sem-assinatura");
    headers.set("X-Hub-Signature-256", "sha256=" + "0".repeat(64));

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "/webhooks/github", new HttpEntity<>(PAYLOAD, headers), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(receive()).isNull();
    assertThat(countRows()).isZero();
  }

  @Test
  void payloadIncompletoResponde400ENaoPublica() {
    String payload = PAYLOAD.replace("\"id\": 42", "\"id\": 0");

    assertThat(post("delivery-incompleta", payload).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    assertThat(receive()).isNull();
    assertThat(countRows()).isZero();
  }

  private ResponseEntity<Void> post(String deliveryId) {
    return post(deliveryId, PAYLOAD);
  }

  private ResponseEntity<Void> post(String deliveryId, String payload) {
    HttpHeaders headers = headers(deliveryId);
    headers.set("X-Hub-Signature-256", signature(payload));
    return restTemplate.postForEntity(
        "/webhooks/github", new HttpEntity<>(payload, headers), Void.class);
  }

  private HttpHeaders headers(String deliveryId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-GitHub-Event", "pull_request");
    headers.set("X-GitHub-Delivery", deliveryId);
    return headers;
  }

  /** Espera generosa na primeira leitura e curta nas seguintes, para o caso "nada publicado". */
  private Message receive() {
    return rabbitTemplate.receive(TEST_QUEUE, 2000);
  }

  /**
   * Desserializa o corpo direto com o {@link ObjectMapper} da aplicação.
   *
   * <p>Não dá para usar o conversor do {@code RabbitTemplate} aqui: sem tipo inferido ele resolve a
   * classe pelo header {@code __TypeId__} e barra tudo fora de {@code java.util}/{@code java.lang}.
   * Os consumidores de produção não passam por isso porque o tipo vem da assinatura do
   * {@code @RabbitListener}.
   */
  private ReviewRequestedEvent convert(Message message) {
    try {
      return objectMapper.readValue(message.getBody(), ReviewRequestedEvent.class);
    } catch (IOException e) {
      throw new IllegalStateException("corpo da mensagem não desserializou", e);
    }
  }

  private int countRows() {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM gateway.webhook_delivery")
        .query(Integer.class)
        .single();
  }

  private static String signature(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256="
          + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("falha ao assinar o payload de teste", e);
    }
  }

  @TestConfiguration
  static class TestQueueConfiguration {

    /**
     * Faz o papel do consumidor: sem uma fila ligada à routing key, a mensagem é descartada.
     *
     * <p>Durável como as filas de produção, e não transiente: o RabbitMQ 4 removeu {@code
     * transient_nonexcl_queues} e derruba a conexão ao receber a declaração. A fila morre junto com
     * o container no fim da classe.
     */
    @Bean
    Declarables testQueue() {
      Queue queue = QueueBuilder.durable(TEST_QUEUE).build();
      Binding binding =
          BindingBuilder.bind(queue)
              .to(new TopicExchange(Topology.EXCHANGE))
              .with(Topology.RK_REVIEW_REQUESTED);
      return new Declarables(queue, binding);
    }
  }
}
