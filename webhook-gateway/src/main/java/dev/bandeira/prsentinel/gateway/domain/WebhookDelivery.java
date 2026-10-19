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
    DeliveryId deliveryId, PullRequestAction action, PullRequestRef pullRequest) {

  public WebhookDelivery {
    if (deliveryId == null) {
      throw new IllegalArgumentException("deliveryId é obrigatório");
    }
    if (action == null) {
      throw new IllegalArgumentException("action é obrigatória");
    }
  }

  /**
   * Se o PR referenciado tem tudo que os serviços seguintes precisam.
   *
   * <p>É uma consulta e não uma validação no construtor de propósito: um payload incompleto de uma
   * ação que não dispara review precisa terminar em {@code 202}, não em erro. Quem decide é o caso
   * de uso, que checa a ação primeiro.
   *
   * <p>O que está na lista não é arbitrário: o {@code diff-fetcher} troca o {@code installationId}
   * pelo token do App e usa owner, repo e número para achar o PR; o {@code headSha} é o que amarra
   * a review ao commit revisado. Publicar com qualquer um deles vazio troca um {@code 400} barato
   * por uma falha três serviços adiante, depois de pagar o diff e três chamadas de LLM.
   */
  public boolean hasCompletePullRequest() {
    return pullRequest != null
        && pullRequest.installationId() > 0
        && isFilled(pullRequest.owner())
        && isFilled(pullRequest.repo())
        && pullRequest.number() > 0
        && isFilled(pullRequest.headSha());
  }

  private static boolean isFilled(String value) {
    return value != null && !value.isBlank();
  }
}
