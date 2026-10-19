package dev.bandeira.prsentinel.orchestrator.domain;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.List;

/**
 * Veredito da review a partir da severidade máxima dos achados (ADR-008).
 *
 * <p>Gravidade, não volume: um SQL injection isolado pesa mais que oito sugestões de nomenclatura,
 * e decidir por contagem inverteria essa relação.
 *
 * <p>Vale igual para review completa e parcial. Quem diz que faltou agente é o {@code partial} do
 * evento, não o veredito — assim ninguém confunde "nada encontrado" com "ninguém olhou".
 */
public final class VerdictPolicy {

  public Verdict decide(List<Finding> findings) {
    if (findings == null || findings.isEmpty()) {
      return Verdict.APPROVED;
    }
    return findings.stream()
        .map(Finding::severity)
        .map(VerdictPolicy::verdictFor)
        .max(VerdictPolicy::byStrictness)
        .orElse(Verdict.APPROVED);
  }

  private static Verdict verdictFor(Severity severity) {
    return switch (severity) {
      case CRITICAL, HIGH -> Verdict.CHANGES_REQUESTED;
      case MEDIUM -> Verdict.ATTENTION;
      case LOW, INFO -> Verdict.APPROVED;
    };
  }

  private static int byStrictness(Verdict left, Verdict right) {
    return Integer.compare(strictness(left), strictness(right));
  }

  private static int strictness(Verdict verdict) {
    return switch (verdict) {
      case APPROVED -> 0;
      case ATTENTION -> 1;
      case CHANGES_REQUESTED -> 2;
    };
  }
}
