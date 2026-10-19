package dev.bandeira.prsentinel.publisher.domain;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Monta o texto que vai para o PR: o resumo da review e os comentários inline.
 *
 * <p>É o único lugar do sistema que escreve para pessoas, então vale dizer o que o texto precisa
 * fazer. Primeiro, não mentir: review parcial diz em letras claras quais agentes não responderam,
 * porque ninguém pode confundir "nenhum problema de segurança" com "o agente de segurança não
 * rodou" (ADR-003). Segundo, não se passar por humano: a assinatura e a confiança de cada achado
 * deixam explícito que a origem é um LLM com limiar de 0.6 (ADR-004).
 *
 * <p>Classe pura, em português como manda o CLAUDE.md para mensagem de review.
 */
public final class ReviewRenderer {

  private static final String MARKER_SEPARATOR = "\n\n";

  /** Review com comentários posicionados: o resumo não repete a lista de achados. */
  public RenderedReview render(ReviewReadyEvent event) {
    return new RenderedReview(summary(event, false), inlineComments(event));
  }

  /**
   * Review só com resumo, usada quando o GitHub recusa os comentários inline (ADR-009).
   *
   * <p>Aqui a lista de achados entra no corpo: sem inline e sem lista, a review não diria onde
   * estão os problemas.
   */
  public RenderedReview renderWithFindingsInBody(ReviewReadyEvent event) {
    return new RenderedReview(summary(event, true), List.of());
  }

  private String summary(ReviewReadyEvent event, boolean includeFindings) {
    StringBuilder body = new StringBuilder();

    body.append("## PR Sentinel — ").append(headline(event.verdict())).append("\n\n");
    body.append(countSentence(event.findings())).append('\n');

    if (event.partial()) {
      body.append('\n').append(partialWarning(event)).append('\n');
    }

    if (!event.findings().isEmpty()) {
      body.append('\n').append(severityTable(event.findings()));
    }

    if (includeFindings && !event.findings().isEmpty()) {
      body.append('\n').append(findingList(event.findings()));
    }

    body.append('\n').append(footer(event));
    body.append(MARKER_SEPARATOR).append(ReviewMarker.of(event.reviewId()));

    return body.toString();
  }

  private static String headline(Verdict verdict) {
    return switch (verdict) {
      case CHANGES_REQUESTED -> "mudanças solicitadas";
      case ATTENTION -> "pontos de atenção";
      case APPROVED -> "nada a apontar";
    };
  }

  private static String countSentence(List<Finding> findings) {
    if (findings.isEmpty()) {
      return "Nenhum achado acima do limiar de confiança.";
    }
    return findings.size() == 1
        ? "1 achado nesta análise."
        : findings.size() + " achados nesta análise.";
  }

  private static String partialWarning(ReviewReadyEvent event) {
    String missing =
        event.missingAgents().stream().map(Enum::name).collect(Collectors.joining(", "));
    return "> **Review parcial.** Estes agentes não responderam no prazo: "
        + missing
        + ". O que eles analisariam não está coberto aqui.";
  }

  private static String severityTable(List<Finding> findings) {
    Map<Severity, Long> counts = countBySeverity(findings);

    StringBuilder table = new StringBuilder("| Severidade | Achados |\n|---|---|\n");
    counts.forEach(
        (severity, count) ->
            table.append("| ").append(severity).append(" | ").append(count).append(" |\n"));
    return table.toString();
  }

  /** Em ordem de gravidade, e sem linha para severidade que não apareceu. */
  private static Map<Severity, Long> countBySeverity(List<Finding> findings) {
    Map<Severity, Long> counts = new LinkedHashMap<>();
    for (Severity severity : Severity.values()) {
      long count = findings.stream().filter(finding -> finding.severity() == severity).count();
      if (count > 0) {
        counts.put(severity, count);
      }
    }
    return counts;
  }

  private static String findingList(List<Finding> findings) {
    StringJoiner list = new StringJoiner("\n", "\n", "\n");
    for (Finding finding : findings) {
      list.add(
          "- **%s** `%s:%d` — %s"
              .formatted(finding.severity(), finding.file(), finding.line(), finding.title()));
    }
    return list.toString();
  }

  private static String footer(ReviewReadyEvent event) {
    return "\n<sub>Gerado pelo PR Sentinel em %s. Achados vêm de análise por LLM e podem conter"
            .formatted(humanDuration(event.durationMs()))
        + " falso positivo — confirme antes de agir.</sub>";
  }

  private static String humanDuration(long durationMs) {
    long seconds = Math.max(durationMs, 0) / 1000;
    if (seconds < 60) {
      return seconds + "s";
    }
    return "%dmin %02ds".formatted(seconds / 60, seconds % 60);
  }

  private List<InlineComment> inlineComments(ReviewReadyEvent event) {
    List<InlineComment> comments = new ArrayList<>(event.findings().size());
    for (Finding finding : event.findings()) {
      comments.add(new InlineComment(finding.file(), finding.line(), commentBody(finding)));
    }
    return List.copyOf(comments);
  }

  private static String commentBody(Finding finding) {
    StringBuilder body = new StringBuilder();
    body.append("**").append(finding.severity()).append(" — ").append(finding.title()).append("**");
    body.append("\n\n").append(finding.explanation());

    if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
      body.append("\n\n**Sugestão**\n").append(finding.suggestion());
    }

    body.append("\n\n<sub>PR Sentinel · confiança ")
        .append(String.format(java.util.Locale.ROOT, "%.2f", finding.confidence()))
        .append("</sub>");
    return body.toString();
  }
}
