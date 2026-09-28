package dev.bandeira.prsentinel.common.event;

import java.time.Instant;
import java.util.UUID;

/** Publicado pelo webhook-gateway quando um PR é aberto ou atualizado. */
public record ReviewRequestedEvent(
        UUID reviewId,
        String deliveryId,
        PullRequestRef pullRequest,
        Instant requestedAt
) {
}
