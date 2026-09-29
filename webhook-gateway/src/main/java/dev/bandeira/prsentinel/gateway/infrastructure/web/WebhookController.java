package dev.bandeira.prsentinel.gateway.infrastructure.web;

import dev.bandeira.prsentinel.gateway.application.DeliveryOutcome;
import dev.bandeira.prsentinel.gateway.application.RequestReviewUseCase;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import dev.bandeira.prsentinel.gateway.infrastructure.security.SignatureVerifier;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador HTTP do webhook do GitHub.
 *
 * <p>Só faz trabalho de transporte: verifica a assinatura, traduz o payload e mapeia o resultado do
 * caso de uso em status. A decisão de publicar ou não é da {@code application}.
 *
 * <p>Todo caminho aceito responde {@code 202}, inclusive duplicata e ação ignorada. Responder
 * não-2xx faria o GitHub reentregar e marcar a entrega como falha permanente no painel do App
 * (ADR-005). A distinção entre os casos fica no log.
 */
@RestController
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  private final SignatureVerifier signatureVerifier;
  private final WebhookPayloadReader payloadReader;
  private final RequestReviewUseCase requestReview;

  public WebhookController(
      SignatureVerifier signatureVerifier,
      WebhookPayloadReader payloadReader,
      RequestReviewUseCase requestReview) {
    this.signatureVerifier = signatureVerifier;
    this.payloadReader = payloadReader;
    this.requestReview = requestReview;
  }

  @PostMapping("/webhooks/github")
  public ResponseEntity<Void> receive(
      @RequestHeader("X-GitHub-Event") String event,
      @RequestHeader("X-GitHub-Delivery") String deliveryId,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestBody byte[] payload)
      throws IOException {

    // A assinatura é conferida sobre o corpo cru, antes de qualquer parse.
    if (!signatureVerifier.isValid(payload, signature)) {
      log.warn("Assinatura inválida delivery={}", deliveryId);
      return ResponseEntity.status(401).build();
    }

    if (!"pull_request".equals(event)) {
      return ResponseEntity.accepted().build();
    }

    WebhookDelivery delivery;
    try {
      delivery = payloadReader.read(payload, deliveryId);
    } catch (IllegalArgumentException e) {
      // deliveryId ausente, vazio ou longo demais: erro do cliente, não vale reentregar.
      log.warn("Delivery id inválido: {}", e.getMessage());
      return ResponseEntity.badRequest().build();
    }

    DeliveryOutcome outcome = requestReview.handle(delivery);

    switch (outcome) {
      case PUBLISHED ->
          log.info(
              "Review solicitada delivery={} pr={}", deliveryId, delivery.pullRequest().fullName());
      case DUPLICATE -> log.info("Entrega duplicada ignorada delivery={}", deliveryId);
      case IGNORED_ACTION ->
          log.debug("Ação sem review delivery={} action={}", deliveryId, delivery.action());
    }

    return ResponseEntity.accepted().build();
  }
}
