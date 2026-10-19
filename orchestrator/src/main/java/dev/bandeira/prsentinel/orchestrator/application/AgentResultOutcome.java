package dev.bandeira.prsentinel.orchestrator.application;

/** Como terminou o processamento de um resultado de agente. O adaptador traduz isto em log. */
public enum AgentResultOutcome {
  /** Gravado, e ainda falta algum agente responder. */
  WAITING_FOR_AGENTS,
  /** Gravado, era o último que faltava, e a review foi publicada. */
  PUBLISHED,
  /** Aquele agente já tinha respondido nesta review; nada foi alterado. */
  DUPLICATE,
  /**
   * Gravado, mas a review já havia sido encerrada — tipicamente pelo timeout (ADR-003). O resultado
   * fica no banco e nenhuma segunda publicação acontece.
   */
  ALREADY_CLOSED
}
