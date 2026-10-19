package dev.bandeira.prsentinel.orchestrator.domain;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforma os achados dos três agentes em uma lista só (ADR-008).
 *
 * <p>A chave é arquivo mais linha: no máximo um achado por linha chega ao PR. Os focos dos agentes
 * se sobrepõem — uma query montada por concatenação é SQL injection para um e violação de camada
 * para outro — e dois comentários na mesma linha dizendo coisas parecidas é o tipo de ruído que faz
 * o time desligar o bot.
 *
 * <p>O que é descartado aqui continua gravado em {@code finding}, ligado ao agente que reportou.
 * Nada se perde no banco; só não vira comentário.
 */
public final class FindingConsolidator {

  public List<Finding> consolidate(List<Finding> findings) {
    if (findings == null || findings.isEmpty()) {
      return List.of();
    }

    // LinkedHashMap preserva a ordem de chegada, que é o critério de desempate final: quando
    // severidade e confiança são iguais, fica quem respondeu primeiro.
    Map<Position, Finding> winners = new LinkedHashMap<>();
    for (Finding finding : findings) {
      winners.merge(Position.of(finding), finding, FindingConsolidator::moreRelevant);
    }

    return winners.values().stream().sorted(bySeverityThenLocation()).toList();
  }

  private static Finding moreRelevant(Finding current, Finding candidate) {
    int bySeverity = Integer.compare(rank(candidate.severity()), rank(current.severity()));
    if (bySeverity != 0) {
      return bySeverity < 0 ? candidate : current;
    }
    return candidate.confidence() > current.confidence() ? candidate : current;
  }

  /** Ordena para o comentário da fase 6 começar pelo que importa. */
  private static Comparator<Finding> bySeverityThenLocation() {
    return Comparator.<Finding>comparingInt(finding -> rank(finding.severity()))
        .thenComparing(Finding::file)
        .thenComparingInt(Finding::line);
  }

  /**
   * Gravidade em número, do mais grave para o menos.
   *
   * <p>Switch exaustivo em vez de {@code ordinal()}: uma severidade nova no {@code common} não
   * compila até alguém dizer onde ela entra na ordem, em vez de assumir silenciosamente a posição
   * de declaração.
   */
  private static int rank(Severity severity) {
    return switch (severity) {
      case CRITICAL -> 0;
      case HIGH -> 1;
      case MEDIUM -> 2;
      case LOW -> 3;
      case INFO -> 4;
    };
  }

  /** Arquivo e linha: a chave de deduplicação da ADR-008. */
  private record Position(String file, int line) {

    static Position of(Finding finding) {
      return new Position(finding.file(), finding.line());
    }
  }
}
