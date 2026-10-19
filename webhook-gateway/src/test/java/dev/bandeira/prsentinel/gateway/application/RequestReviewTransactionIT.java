package dev.bandeira.prsentinel.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.gateway.application.port.ReviewRequestPublisher;
import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova que o limite transacional da ADR-005 existe de fato.
 *
 * <p>A garantia central da decisão é negativa e invisível em teste unitário: se a publicação falhar
 * depois da reserva, a reserva tem de desaparecer. O {@link RequestReviewUseCase} é uma classe
 * concreta, sem interface, criada por {@code @Bean} — se o proxy transacional não entrar, o
 * {@code @Transactional} não faz nada, cada teste de unidade continua verde e o sistema passa a
 * marcar como processada uma entrega cujo evento nunca foi publicado. Essa review simplesmente
 * nunca acontece, e a reentrega é descartada como duplicada.
 *
 * <p>Por isso o teste vai pelo contexto Spring real, com Postgres de verdade, e troca só o
 * publisher. Sem RabbitMQ: nada aqui chega a publicar de verdade.
 */
@Testcontainers
@SpringBootTest
@Import(RequestReviewTransactionIT.SwitchablePublisherConfiguration.class)
class RequestReviewTransactionIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("prsentinel")
          .withUsername("prsentinel")
          .withPassword("prsentinel");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("github.webhook.secret", () -> "segredo-de-teste");
  }

  @Autowired private RequestReviewUseCase useCase;
  @Autowired private SwitchablePublisher publisher;
  @Autowired private JdbcClient jdbcClient;

  @BeforeEach
  void limpaEstado() {
    jdbcClient.sql("DELETE FROM gateway.webhook_delivery").update();
    publisher.reset();
  }

  @Test
  void oCasoDeUsoEhEnvolvidoPorProxyTransacional() {
    // Redundante com o teste de rollback abaixo, e de propósito: quando o rollback quebrar, este
    // aqui diz na hora se a causa foi o proxy ter sumido.
    assertThat(AopUtils.isAopProxy(useCase))
        .as("sem proxy não há transação, e o @Transactional da ADR-005 vira decoração")
        .isTrue();
  }

  @Test
  void publicacaoBemSucedidaDeixaAReservaGravada() {
    DeliveryOutcome outcome = useCase.handle(delivery("delivery-commit"));

    assertThat(outcome).isEqualTo(DeliveryOutcome.PUBLISHED);
    assertThat(publisher.published()).hasSize(1);
    assertThat(countRows()).isEqualTo(1);
  }

  @Test
  void falhaAoPublicarDesfazAReserva() {
    publisher.failNext();

    assertThatThrownBy(() -> useCase.handle(delivery("delivery-rollback")))
        .isInstanceOf(AmqpException.class);

    assertThat(countRows())
        .as(
            "a reserva precisa ter sofrido rollback junto com a publicação, senão a reentrega do"
                + " GitHub é descartada como duplicada e a review nunca acontece")
        .isZero();
  }

  @Test
  void entregaSeguinteFuncionaDepoisDeUmRollback() {
    publisher.failNext();
    assertThatThrownBy(() -> useCase.handle(delivery("delivery-rollback")))
        .isInstanceOf(AmqpException.class);

    // O mesmo delivery id tem de voltar a ser aceito: é exatamente o que o GitHub vai reentregar.
    DeliveryOutcome outcome = useCase.handle(delivery("delivery-rollback"));

    assertThat(outcome).isEqualTo(DeliveryOutcome.PUBLISHED);
    assertThat(countRows()).isEqualTo(1);
  }

  private WebhookDelivery delivery(String deliveryId) {
    return new WebhookDelivery(
        new DeliveryId(deliveryId),
        PullRequestAction.OPENED,
        new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123"));
  }

  private int countRows() {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM gateway.webhook_delivery")
        .query(Integer.class)
        .single();
  }

  @TestConfiguration
  static class SwitchablePublisherConfiguration {

    /**
     * Substitui o publisher real. {@code @Primary} em vez de remover o bean do Rabbit: o contexto
     * continua o de produção, e o que muda é só quem o caso de uso recebe.
     */
    @Bean
    @Primary
    SwitchablePublisher switchablePublisher() {
      return new SwitchablePublisher();
    }
  }

  /** Publisher que pode ser mandado falhar, para exercitar os dois lados da transação. */
  static class SwitchablePublisher implements ReviewRequestPublisher {

    private final List<ReviewRequestedEvent> published = new ArrayList<>();
    private boolean shouldFail;

    void failNext() {
      this.shouldFail = true;
    }

    void reset() {
      this.shouldFail = false;
      this.published.clear();
    }

    List<ReviewRequestedEvent> published() {
      return List.copyOf(published);
    }

    @Override
    public void publish(ReviewRequestedEvent event) {
      if (shouldFail) {
        shouldFail = false;
        throw new AmqpException("broker indisponível");
      }
      published.add(event);
    }
  }
}
