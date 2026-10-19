package dev.bandeira.prsentinel.orchestrator.domain;

/**
 * Estado de uma review.
 *
 * <p>Os nomes são os mesmos do {@code ck_review_status} da migration — divergir aqui vira erro de
 * constraint no primeiro UPDATE, em produção.
 *
 * <p>A separação entre aberto e encerrado é o que a varredura de timeout usa para saber o que ainda
 * precisa de atenção.
 */
public enum ReviewStatus {
  /** Registrada, nenhum agente respondeu ainda. */
  PENDING,
  /** Pelo menos um agente respondeu, faltam outros. */
  IN_PROGRESS,
  /** Os três agentes responderam e a review foi publicada. */
  COMPLETED,
  /** O timeout estourou e a review foi publicada com quem respondeu (ADR-003). */
  PARTIAL,
  /** Nenhum agente respondeu até o timeout. */
  FAILED;

  public boolean isOpen() {
    return this == PENDING || this == IN_PROGRESS;
  }

  public boolean isFinished() {
    return !isOpen();
  }
}
