package dev.bandeira.prsentinel.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tradução do corpo cru do webhook para o domínio.
 *
 * <p>Os payloads são montados com {@link ObjectNode} em vez de texto fixo justamente para que os
 * casos negativos removam um campo do payload completo — assim o teste de campo ausente não pode
 * divergir do payload válido com o tempo.
 */
class WebhookPayloadReaderTest {

  private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final WebhookPayloadReader reader = new WebhookPayloadReader(objectMapper);

  @Test
  void extraiTodosOsCamposDoPayloadCompleto() {
    WebhookDelivery delivery = reader.read(bytes(fullPayload()), DELIVERY_ID);

    assertThat(delivery.deliveryId().value()).isEqualTo(DELIVERY_ID);
    assertThat(delivery.action()).isEqualTo(PullRequestAction.OPENED);
    assertThat(delivery.pullRequest().installationId()).isEqualTo(42L);
    assertThat(delivery.pullRequest().owner()).isEqualTo("devBandeiraa");
    assertThat(delivery.pullRequest().repo()).isEqualTo("pr-sentinel");
    assertThat(delivery.pullRequest().number()).isEqualTo(7);
    assertThat(delivery.pullRequest().headSha()).isEqualTo("abc123");
  }

  @Test
  void acaoDesconhecidaViraOtherSemFalhar() {
    ObjectNode payload = fullPayload();
    payload.put("action", "labeled");

    WebhookDelivery delivery = reader.read(bytes(payload), DELIVERY_ID);

    assertThat(delivery.action()).isEqualTo(PullRequestAction.OTHER);
  }

  @Test
  void campoAusenteViraValorVazioEmVezDeFalhar() {
    // O reader traduz, não julga: quem decide se a entrega dá para publicar é o domínio, porque a
    // decisão depende da ação — payload incompleto de ação que não dispara review é para ser
    // ignorado, não recusado.
    ObjectNode payload = fullPayload();
    payload.remove("installation");

    WebhookDelivery delivery = reader.read(bytes(payload), DELIVERY_ID);

    assertThat(delivery.pullRequest().installationId()).isZero();
    assertThat(delivery.action()).isEqualTo(PullRequestAction.OPENED);
  }

  @Test
  void jsonMalformadoViraIllegalArgumentException() {
    byte[] malformed = "{\"action\": \"opened\"".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> reader.read(malformed, DELIVERY_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mensagemDeErroDeParseNaoVazaOCorpoDoWebhook() {
    // O controller loga a mensagem desta exceção. A do Jackson carrega um trecho do corpo cru
    // junto, e corpo de webhook em log é proibido pelo CLAUDE.md — daí a mensagem própria.
    byte[] malformed =
        "{\"action\": \"opened\", \"conteudo-do-pr\": \"nao-pode-ir-para-o-log\""
            .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> reader.read(malformed, DELIVERY_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("nao-pode-ir-para-o-log")
        .hasMessageNotContaining("conteudo-do-pr");
  }

  @Test
  void deliveryIdInvalidoEhRejeitadoNaLeitura() {
    assertThatThrownBy(() -> reader.read(bytes(fullPayload()), "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ObjectNode fullPayload() {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("action", "opened");
    root.putObject("installation").put("id", 42);

    ObjectNode repository = root.putObject("repository");
    repository.put("name", "pr-sentinel");
    repository.putObject("owner").put("login", "devBandeiraa");

    ObjectNode pullRequest = root.putObject("pull_request");
    pullRequest.put("number", 7);
    pullRequest.putObject("head").put("sha", "abc123");

    return root;
  }

  private byte[] bytes(ObjectNode node) {
    return node.toString().getBytes(StandardCharsets.UTF_8);
  }
}
