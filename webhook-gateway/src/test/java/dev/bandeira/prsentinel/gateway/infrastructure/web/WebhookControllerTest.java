package dev.bandeira.prsentinel.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.gateway.application.DeliveryOutcome;
import dev.bandeira.prsentinel.gateway.application.RequestReviewUseCase;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import dev.bandeira.prsentinel.gateway.infrastructure.config.WebhookProperties;
import dev.bandeira.prsentinel.gateway.infrastructure.security.SignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Contrato HTTP do webhook.
 *
 * <p>Monta o controller com {@code standaloneSetup} e com o {@link SignatureVerifier} e o {@link
 * WebhookPayloadReader} reais: a assinatura é calculada de verdade no teste, então a ordem
 * "verifica o HMAC sobre o corpo cru antes de qualquer parse" fica exercida em vez de presumida. Só
 * o caso de uso é dublado, porque é a decisão dele que o controller precisa traduzir em status.
 */
class WebhookControllerTest {

  private static final String SECRET = "segredo-de-teste";
  private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
  private static final String PAYLOAD =
      """
      {
        "action": "opened",
        "installation": { "id": 42 },
        "repository": { "name": "pr-sentinel", "owner": { "login": "devBandeiraa" } },
        "pull_request": { "number": 7, "head": { "sha": "abc123" } }
      }
      """;

  private final RequestReviewUseCase requestReview = mock(RequestReviewUseCase.class);

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(
              new WebhookController(
                  new SignatureVerifier(new WebhookProperties(SECRET)),
                  new WebhookPayloadReader(new ObjectMapper()),
                  requestReview))
          .build();

  @ParameterizedTest
  @EnumSource(
      value = DeliveryOutcome.class,
      names = {"PUBLISHED", "DUPLICATE", "IGNORED_ACTION"})
  void todoResultadoDeSucessoResponde202(DeliveryOutcome outcome) throws Exception {
    when(requestReview.handle(any())).thenReturn(outcome);

    mockMvc.perform(webhookRequest(PAYLOAD)).andExpect(status().isAccepted());
  }

  @Test
  void payloadIncompletoResponde400ParaOGitHubNaoReentregar() throws Exception {
    when(requestReview.handle(any())).thenReturn(DeliveryOutcome.INVALID_PAYLOAD);

    mockMvc.perform(webhookRequest(PAYLOAD)).andExpect(status().isBadRequest());
  }

  @Test
  void assinaturaInvalidaResponde401ENaoChegaNoCasoDeUso() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID)
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD))
        .andExpect(status().isUnauthorized());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void assinaturaAusenteResponde401() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD))
        .andExpect(status().isUnauthorized());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void corpoAlteradoDepoisDeAssinadoResponde401() throws Exception {
    String tampered = PAYLOAD.replace("\"number\": 7", "\"number\": 8");

    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID)
                .header("X-Hub-Signature-256", signature(PAYLOAD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tampered))
        .andExpect(status().isUnauthorized());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void eventoQueNaoEhPullRequestResponde202SemChamarOCasoDeUso() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "push")
                .header("X-GitHub-Delivery", DELIVERY_ID)
                .header("X-Hub-Signature-256", signature(PAYLOAD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD))
        .andExpect(status().isAccepted());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void deliveryIdEmBrancoResponde400() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "   ")
                .header("X-Hub-Signature-256", signature(PAYLOAD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD))
        .andExpect(status().isBadRequest());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void deliveryIdLongoDemaisResponde400() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "a".repeat(65))
                .header("X-Hub-Signature-256", signature(PAYLOAD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(PAYLOAD))
        .andExpect(status().isBadRequest());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void jsonMalformadoResponde400EmVezDe500() throws Exception {
    // 500 aqui seria o pior status possível: o GitHub reentregaria indefinidamente um corpo
    // que nunca vai parsear.
    String malformed = "{\"action\": \"opened\"";

    mockMvc.perform(webhookRequest(malformed)).andExpect(status().isBadRequest());

    verify(requestReview, never()).handle(any());
  }

  @Test
  void aAssinaturaEhConferidaAntesDoParseDoCorpo() throws Exception {
    // Mesmo corpo malformado do teste acima, agora sem assinatura válida: a resposta tem de ser
    // 401, não 400 — prova que nada do corpo é interpretado antes do HMAC.
    mockMvc
        .perform(
            post("/webhooks/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID)
                .header("X-Hub-Signature-256", "sha256=" + "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"opened\""))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void oQueChegaNoCasoDeUsoEhOQueVeioNoPayload() throws Exception {
    when(requestReview.handle(any())).thenReturn(DeliveryOutcome.PUBLISHED);

    mockMvc.perform(webhookRequest(PAYLOAD)).andExpect(status().isAccepted());

    ArgumentCaptor<WebhookDelivery> captor = ArgumentCaptor.forClass(WebhookDelivery.class);
    verify(requestReview).handle(captor.capture());

    WebhookDelivery delivery = captor.getValue();
    assertThat(delivery.deliveryId().value()).isEqualTo(DELIVERY_ID);
    assertThat(delivery.pullRequest().fullName()).isEqualTo("devBandeiraa/pr-sentinel#7");
  }

  private MockHttpServletRequestBuilder webhookRequest(String body) {
    return post("/webhooks/github")
        .header("X-GitHub-Event", "pull_request")
        .header("X-GitHub-Delivery", DELIVERY_ID)
        .header("X-Hub-Signature-256", signature(body))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
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
}
