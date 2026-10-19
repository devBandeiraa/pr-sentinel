package dev.bandeira.prsentinel.gateway.infrastructure.web;

import dev.bandeira.prsentinel.gateway.application.RequestReviewUseCase;
import dev.bandeira.prsentinel.gateway.domain.WebhookDelivery;
import dev.bandeira.prsentinel.gateway.infrastructure.security.SignatureVerifier;
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
 *
 * <p>Os {@code 4xx} são reservados ao que reentregar não conserta: assinatura inválida ({@code
 * 401}), delivery id imprestável, corpo que não é JSON e payload de PR incompleto ({@code 400}).
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
      @RequestBody byte[] payload) {

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
      // Delivery id imprestável ou corpo que não é JSON: erro do cliente, reentregar não conserta.
      // A mensagem é sempre de produção nossa, nunca a do Jackson, que carregaria o corpo cru.
      log.warn("Entrega recusada delivery={} motivo={}", deliveryId, e.getMessage());
      return ResponseEntity.badRequest().build();
    }

    // switch como expressão, e não como comando: assim um DeliveryOutcome novo não compila até
    // alguém decidir qual status ele devolve, em vez de cair silenciosamente num 202.
    return switch (requestReview.handle(delivery)) {
      case PUBLISHED -> {
        log.info(
            "Review solicitada delivery={} pr={}", deliveryId, delivery.pullRequest().fullName());
        yield ResponseEntity.accepted().build();
      }
      case DUPLICATE -> {
        log.info("Entrega duplicada ignorada delivery={}", deliveryId);
        yield ResponseEntity.accepted().build();
      }
      case IGNORED_ACTION -> {
        log.debug("Ação sem review delivery={} action={}", deliveryId, delivery.action());
        yield ResponseEntity.accepted().build();
      }
      case INVALID_PAYLOAD -> {
        log.warn("Payload de pull_request incompleto delivery={}", deliveryId);
        yield ResponseEntity.badRequest().build();
      }
    };
  }
}
