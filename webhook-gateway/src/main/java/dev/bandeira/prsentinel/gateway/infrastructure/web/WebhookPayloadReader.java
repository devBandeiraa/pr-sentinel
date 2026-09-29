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
 */
@Component
public class WebhookPayloadReader {

  private final ObjectMapper objectMapper;

  public WebhookPayloadReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public WebhookDelivery read(byte[] payload, String rawDeliveryId) throws IOException {
    JsonNode body = objectMapper.readTree(payload);
    JsonNode pullRequest = body.path("pull_request");

    PullRequestRef ref =
        new PullRequestRef(
            body.path("installation").path("id").asLong(),
            body.path("repository").path("owner").path("login").asText(),
            body.path("repository").path("name").asText(),
            pullRequest.path("number").asInt(),
            pullRequest.path("head").path("sha").asText());

    return new WebhookDelivery(
        new DeliveryId(rawDeliveryId), PullRequestAction.from(body.path("action").asText()), ref);
  }
}
