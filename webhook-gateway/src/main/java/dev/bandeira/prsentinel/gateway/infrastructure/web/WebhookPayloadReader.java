package dev.bandeira.prsentinel.gateway.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.gateway.domain.DeliveryId;
import dev.bandeira.prsentinel.gateway.domain.PullRequestAction;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Traduz o corpo cru do webhook num {@link WebhookDelivery} do domínio.
 *
 * <p>Existe para que Jackson não vaze do adaptador HTTP: {@code JsonNode} não pode aparecer no
 * domain nem na application, e é o {@code ArchitectureTest} que garante isso.
 *
 * <p>O payload é entrada não confiável mesmo depois do HMAC — quem controla o repositório controla
 * o conteúdo. Por isso os campos são lidos por caminho tolerante ({@code path}), que devolve nó
 * vazio em vez de {@code null}, e o {@link DeliveryId} valida o que veio do header.
 *
 * <p>Esta classe traduz, não julga: campo ausente vira valor vazio e quem decide se a entrega é
 * aproveitável é {@link WebhookDelivery#hasCompletePullRequest()}, no domínio. A exceção são as
 * duas falhas em que não há nada para traduzir — delivery id inválido e JSON malformado.
 */
@Component
public class WebhookPayloadReader {

  private final ObjectMapper objectMapper;

  public WebhookPayloadReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * @throws IllegalArgumentException se o delivery id não presta ou o corpo não é um JSON válido —
   *     os dois casos em que não há o que reentregar, e que o adaptador HTTP traduz em {@code 400}
   */
  public WebhookDelivery read(byte[] payload, String rawDeliveryId) {
    // O header vem primeiro: é a checagem mais barata, e não há motivo para interpretar o corpo
    // de uma entrega cujo identificador já está inválido.
    DeliveryId deliveryId = new DeliveryId(rawDeliveryId);

    JsonNode body = parse(payload);
    JsonNode pullRequest = body.path("pull_request");

    PullRequestRef ref =
        new PullRequestRef(
            body.path("installation").path("id").asLong(),
            body.path("repository").path("owner").path("login").asText(),
            body.path("repository").path("name").asText(),
            pullRequest.path("number").asInt(),
            pullRequest.path("head").path("sha").asText());

    return new WebhookDelivery(
        deliveryId, PullRequestAction.from(body.path("action").asText()), ref);
  }

  private JsonNode parse(byte[] payload) {
    try {
      return objectMapper.readTree(payload);
    } catch (IOException e) {
      // Mensagem própria, e a exceção do Jackson deliberadamente não vai como causa: ela carrega
      // um trecho do corpo cru, e corpo de webhook em log é proibido pelo CLAUDE.md. Quem loga
      // esta exceção lá na frente não tem como vazar o payload sem querer.
      throw new IllegalArgumentException("corpo do webhook não é um JSON válido");
    }
  }
}
