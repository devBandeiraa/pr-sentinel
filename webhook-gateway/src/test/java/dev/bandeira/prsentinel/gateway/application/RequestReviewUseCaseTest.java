package dev.bandeira.prsentinel.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Orquestração do {@link RequestReviewUseCase} usando fakes das portas — nenhum Mockito aqui porque
 * os fakes já expressam a semântica real (registrar é idempotente, publicar pode falhar) sem
 * esconder comportamento atrás de stubs.
 */
class RequestReviewUseCaseTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-09-29T10:15:30Z");

  private final FakeProcessedDeliveries processedDeliveries = new FakeProcessedDeliveries();
  private final FakeReviewRequestPublisher publisher = new FakeReviewRequestPublisher();
  private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private final RequestReviewUseCase useCase =
      new RequestReviewUseCase(processedDeliveries, publisher, clock);

  private final PullRequestRef pullRequest =
      new PullRequestRef(42L, "bandeira", "pr-sentinel", 7, "abc123");

  @Test
  void acaoQueNaoDisparaReviewNaoRegistraNemPublica() {
    WebhookDelivery delivery =
        new WebhookDelivery(
            new DeliveryId("delivery-ignored"), PullRequestAction.OTHER, pullRequest);

    DeliveryOutcome outcome = useCase.handle(delivery);

    assertThat(outcome).isEqualTo(DeliveryOutcome.IGNORED_ACTION);
    assertThat(processedDeliveries.registerCalls()).isEmpty();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void primeiraEntregaRegistraEPublicaExatamenteUmEvento() {
    DeliveryId deliveryId = new DeliveryId("delivery-first");
    WebhookDelivery delivery =
        new WebhookDelivery(deliveryId, PullRequestAction.OPENED, pullRequest);

    DeliveryOutcome outcome = useCase.handle(delivery);

    assertThat(outcome).isEqualTo(DeliveryOutcome.PUBLISHED);
    assertThat(publisher.published()).hasSize(1);

    ReviewRequestedEvent event = publisher.published().get(0);
    assertThat(event.deliveryId()).isEqualTo(deliveryId.value());
    assertThat(event.pullRequest()).isEqualTo(pullRequest);
    assertThat(event.requestedAt()).isEqualTo(FIXED_NOW);

    assertThat(processedDeliveries.registerCalls()).hasSize(1);
    assertThat(processedDeliveries.registerCalls().get(0).reviewId()).isEqualTo(event.reviewId());
  }

  @Test
  void deliveryJaRegistradaNaoPublicaNadaEDevolveDuplicate() {
    DeliveryId deliveryId = new DeliveryId("delivery-duplicate");
    processedDeliveries.seedAlreadyRegistered(deliveryId.value());
    WebhookDelivery delivery =
        new WebhookDelivery(deliveryId, PullRequestAction.OPENED, pullRequest);

    DeliveryOutcome outcome = useCase.handle(delivery);

    assertThat(outcome).isEqualTo(DeliveryOutcome.DUPLICATE);
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void falhaAoPublicarPropagaAExcecao() {
    DeliveryId deliveryId = new DeliveryId("delivery-failure");
    WebhookDelivery delivery =
        new WebhookDelivery(deliveryId, PullRequestAction.OPENED, pullRequest);
    publisher.failWith(new RuntimeException("broker indisponível"));

    assertThatThrownBy(() -> useCase.handle(delivery)).isInstanceOf(RuntimeException.class);

    // A reserva já tinha sido feita antes da falha na publicação — é essa ordem que sustenta
    // o rollback transacional descrito na ADR-005.
    assertThat(processedDeliveries.registerCalls()).hasSize(1);
  }

  @Test
  void payloadIncompletoNaoRegistraNemPublicaEDevolveInvalidPayload() {
    WebhookDelivery delivery =
        new WebhookDelivery(
            new DeliveryId("delivery-incomplete"),
            PullRequestAction.OPENED,
            new PullRequestRef(0L, "", "", 0, ""));

    DeliveryOutcome outcome = useCase.handle(delivery);

    assertThat(outcome).isEqualTo(DeliveryOutcome.INVALID_PAYLOAD);
    assertThat(processedDeliveries.registerCalls()).isEmpty();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void acaoIgnoradaVemAntesDaChecagemDePayload() {
    // A ordem é deliberada: um payload incompleto de uma ação que não dispara review precisa
    // terminar em 202, não em 400. Invertido, o gateway recusaria entregas legítimas de
    // 'closed' e o GitHub reentregaria para sempre.
    WebhookDelivery delivery =
        new WebhookDelivery(
            new DeliveryId("delivery-closed-incomplete"),
            PullRequestAction.OTHER,
            new PullRequestRef(0L, "", "", 0, ""));

    DeliveryOutcome outcome = useCase.handle(delivery);

    assertThat(outcome).isEqualTo(DeliveryOutcome.IGNORED_ACTION);
  }
}
