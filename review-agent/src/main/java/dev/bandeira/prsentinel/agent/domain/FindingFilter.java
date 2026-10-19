package dev.bandeira.prsentinel.agent.domain;

import dev.bandeira.prsentinel.common.event.Finding;
import java.util.List;
import java.util.Objects;

/**
 * Decide quais achados do LLM entram na review.
 *
 * <p>A saída do modelo é entrada não confiável, como o payload do webhook: ele inventa número de
 * linha, devolve confiança fora da faixa e às vezes manda achado sem explicação. Pela ADR-004,
 * falso positivo custa mais que falso negativo — então o que não dá para confiar é descartado em
 * vez de ser "consertado".
 *
 * <p>Dois critérios, com motivos diferentes. A <b>confiança</b> abaixo do limiar é o filtro de
 * qualidade da ADR-004. A <b>forma</b> é mais básica: um achado sem arquivo, sem linha válida ou
 * sem explicação não tem como ser mostrado a ninguém — na fase 6 ele viraria comentário inline, e
 * comentário sem posição não existe.
 */
public final class FindingFilter {

  private final double minConfidence;

  public FindingFilter(double minConfidence) {
    if (Double.isNaN(minConfidence) || minConfidence < 0.0 || minConfidence > 1.0) {
      throw new IllegalArgumentException(
          "minConfidence precisa estar entre 0.0 e 1.0: " + minConfidence);
    }
    this.minConfidence = minConfidence;
  }

  public List<Finding> apply(List<Finding> findings) {
    if (findings == null) {
      return List.of();
    }
    return findings.stream()
        .filter(Objects::nonNull)
        .filter(FindingFilter::isWellFormed)
        .filter(this::isConfidentEnough)
        .toList();
  }

  private static boolean isWellFormed(Finding finding) {
    return isFilled(finding.file())
        && finding.severity() != null
        && isFilled(finding.title())
        && isFilled(finding.explanation())
        // Linha é a do arquivo novo e começa em 1. Zero costuma ser o modelo dizendo "não sei".
        && finding.line() >= 1
        && isInRange(finding.confidence());
  }

  private boolean isConfidentEnough(Finding finding) {
    return finding.confidence() >= minConfidence;
  }

  private static boolean isFilled(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean isInRange(double confidence) {
    return !Double.isNaN(confidence) && confidence >= 0.0 && confidence <= 1.0;
  }
}
