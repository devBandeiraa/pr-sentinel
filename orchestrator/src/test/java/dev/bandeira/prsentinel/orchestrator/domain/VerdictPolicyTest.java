package dev.bandeira.prsentinel.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.ReviewReadyEvent.Verdict;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Veredito a partir da severidade máxima (ADR-008).
 *
 * <p>Gravidade, não volume: um SQL injection isolado pesa mais que oito sugestões de nomenclatura.
 */
class VerdictPolicyTest {

  private final VerdictPolicy policy = new VerdictPolicy();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "CRITICAL, CHANGES_REQUESTED",
    "HIGH, CHANGES_REQUESTED",
    "MEDIUM, ATTENTION",
    "LOW, APPROVED",
    "INFO, APPROVED"
  })
  void decidePelaPiorSeveridadePresente(Severity severity, Verdict expected) {
    assertThat(policy.decide(List.of(finding(severity)))).isEqualTo(expected);
  }

  @Test
  void semAchadoEhAprovado() {
    assertThat(policy.decide(List.of())).isEqualTo(Verdict.APPROVED);
    assertThat(policy.decide(null)).isEqualTo(Verdict.APPROVED);
  }

  @Test
  void aPiorSeveridadeManda() {
    assertThat(policy.decide(List.of(finding(Severity.INFO), finding(Severity.CRITICAL))))
        .isEqualTo(Verdict.CHANGES_REQUESTED);
    assertThat(policy.decide(List.of(finding(Severity.LOW), finding(Severity.MEDIUM))))
        .isEqualTo(Verdict.ATTENTION);
    assertThat(policy.decide(List.of(finding(Severity.LOW), finding(Severity.INFO))))
        .isEqualTo(Verdict.APPROVED);
  }

  @Test
  void volumeDeAchadosBaixosNaoViraPedidoDeMudanca() {
    // Dez sugestões de estilo não pesam mais que um problema grave.
    List<Finding> dezInfos =
        java.util.stream.IntStream.range(0, 10).mapToObj(i -> finding(Severity.INFO)).toList();

    assertThat(policy.decide(dezInfos)).isEqualTo(Verdict.APPROVED);
  }

  private static Finding finding(Severity severity) {
    return new Finding("a.java", 10, severity, "t", "e", "s", 0.9);
  }
}
