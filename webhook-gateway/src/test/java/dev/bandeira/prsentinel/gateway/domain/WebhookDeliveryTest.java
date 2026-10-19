package dev.bandeira.prsentinel.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.PullRequestRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * O que conta como entrega completa.
 *
 * <p>Um campo faltando aqui não é detalhe cosmético: o evento publicado alimenta o diff-fetcher,
 * que precisa de {@code installationId} para pegar o token do App e de owner/repo/number para achar
 * o PR. Publicar com campo vazio troca um 400 barato por uma falha três serviços adiante, depois de
 * pagar o diff e três chamadas de LLM.
 */
class WebhookDeliveryTest {

  private static final DeliveryId DELIVERY_ID = new DeliveryId("delivery-1");

  @Test
  void entregaComTodosOsCamposEhCompleta() {
    WebhookDelivery delivery = deliveryWith(completeRef());

    assertThat(delivery.hasCompletePullRequest()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refsIncompletas")
  void entregaComCampoEssencialFaltandoNaoEhCompleta(String caso, PullRequestRef ref) {
    WebhookDelivery delivery = deliveryWith(ref);

    assertThat(delivery.hasCompletePullRequest()).isFalse();
  }

  static Object[][] refsIncompletas() {
    return new Object[][] {
      {"installationId zero", new PullRequestRef(0L, "devBandeiraa", "pr-sentinel", 7, "abc123")},
      {
        "installationId negativo",
        new PullRequestRef(-1L, "devBandeiraa", "pr-sentinel", 7, "abc123")
      },
      {"owner vazio", new PullRequestRef(42L, "", "pr-sentinel", 7, "abc123")},
      {"owner em branco", new PullRequestRef(42L, "   ", "pr-sentinel", 7, "abc123")},
      {"owner nulo", new PullRequestRef(42L, null, "pr-sentinel", 7, "abc123")},
      {"repo vazio", new PullRequestRef(42L, "devBandeiraa", "", 7, "abc123")},
      {"repo nulo", new PullRequestRef(42L, "devBandeiraa", null, 7, "abc123")},
      {"number zero", new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 0, "abc123")},
      {"number negativo", new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", -3, "abc123")},
      {"headSha vazio", new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "")},
      {"headSha nulo", new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, null)},
    };
  }

  @Test
  void entregaSemPullRequestNaoEhCompleta() {
    WebhookDelivery delivery = new WebhookDelivery(DELIVERY_ID, PullRequestAction.OPENED, null);

    assertThat(delivery.hasCompletePullRequest()).isFalse();
  }

  @Test
  void rejeitaEntregaSemDeliveryIdOuSemAcao() {
    assertThatThrownBy(() -> new WebhookDelivery(null, PullRequestAction.OPENED, completeRef()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookDelivery(DELIVERY_ID, null, completeRef()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PullRequestRef completeRef() {
    return new PullRequestRef(42L, "devBandeiraa", "pr-sentinel", 7, "abc123");
  }

  private static WebhookDelivery deliveryWith(PullRequestRef ref) {
    return new WebhookDelivery(DELIVERY_ID, PullRequestAction.OPENED, ref);
  }
}
