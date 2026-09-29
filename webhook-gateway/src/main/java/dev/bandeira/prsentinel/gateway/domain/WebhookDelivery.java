package dev.bandeira.prsentinel.gateway.domain;

import dev.bandeira.prsentinel.common.event.PullRequestRef;

/**
 * Uma entrega de webhook já interpretada: o que o gateway extraiu do payload e precisa para
 * decidir.
 *
 * <p>{@link PullRequestRef} vem do {@code common} e é um record puro, sem framework, então usá-lo
 * aqui não viola a regra de camada validada pelo {@code ArchitectureTest}.
 */
public record WebhookDelivery(
    DeliveryId deliveryId, PullRequestAction action, PullRequestRef pullRequest) {}
