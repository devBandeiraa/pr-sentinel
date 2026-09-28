package dev.bandeira.prsentinel.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bandeira.prsentinel.common.event.PullRequestRef;
import dev.bandeira.prsentinel.common.event.ReviewRequestedEvent;
import dev.bandeira.prsentinel.common.messaging.Topology;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> HANDLED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final SignatureVerifier signatureVerifier;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public WebhookController(SignatureVerifier signatureVerifier,
                             RabbitTemplate rabbitTemplate,
                             ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> receive(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] payload) throws IOException {

        if (!signatureVerifier.isValid(payload, signature)) {
            log.warn("Assinatura inválida delivery={}", deliveryId);
            return ResponseEntity.status(401).build();
        }

        if (!"pull_request".equals(event)) {
            return ResponseEntity.accepted().build();
        }

        JsonNode body = objectMapper.readTree(payload);
        String action = body.path("action").asText();
        if (!HANDLED_ACTIONS.contains(action)) {
            return ResponseEntity.accepted().build();
        }

        // TODO fase 2: idempotência — ignorar deliveryId já processado (Redis ou tabela no Postgres)

        JsonNode pr = body.path("pull_request");
        PullRequestRef ref = new PullRequestRef(
                body.path("installation").path("id").asLong(),
                body.path("repository").path("owner").path("login").asText(),
                body.path("repository").path("name").asText(),
                pr.path("number").asInt(),
                pr.path("head").path("sha").asText());

        ReviewRequestedEvent requested = new ReviewRequestedEvent(
                UUID.randomUUID(), deliveryId, ref, Instant.now());

        rabbitTemplate.convertAndSend(Topology.EXCHANGE, Topology.RK_REVIEW_REQUESTED, requested, message -> {
            message.getMessageProperties().setHeader(Topology.CORRELATION_HEADER, requested.reviewId().toString());
            return message;
        });

        log.info("Review solicitada review={} pr={}", requested.reviewId(), ref.fullName());
        return ResponseEntity.accepted().build();
    }
}
