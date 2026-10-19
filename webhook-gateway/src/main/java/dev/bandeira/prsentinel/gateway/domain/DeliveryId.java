package dev.bandeira.prsentinel.gateway.domain;

/**
 * Identificador de uma entrega de webhook, vindo do header {@code X-GitHub-Delivery}.
 *
 * <p>É a chave de idempotência do sistema (ADR-005): estável entre reentregas da mesma entrega.
 *
 * <p>A validação existe porque o header é entrada externa — mesmo chegando depois da verificação
 * HMAC, um valor vazio ou maior que a coluna não pode alcançar o banco. O limite de 64 caracteres
 * casa com {@code webhook_delivery.delivery_id}.
 */
public record DeliveryId(String value) {

  private static final int MAX_LENGTH = 64;

  public DeliveryId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("deliveryId não pode ser nulo nem em branco");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "deliveryId excede " + MAX_LENGTH + " caracteres: " + value.length());
    }
  }
}
