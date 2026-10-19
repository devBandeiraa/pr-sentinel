package dev.bandeira.prsentinel.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * O que passa do LLM para a review.
 *
 * <p>A saída do modelo é entrada não confiável: ele inventa linha, devolve confiança fora da faixa
 * e às vezes manda achado sem explicação. Pela <a
 * href="../../../../../../../../docs/adr/004-saida-estruturada-do-llm-com-limiar-de-confianca.md">ADR-004</a>
 * falso positivo custa mais que falso negativo, então na dúvida o achado é descartado.
 */
class FindingFilterTest {

  private final FindingFilter filter = new FindingFilter(0.6);

  @Test
  void mantemAchadoCompletoAcimaDoLimiar() {
    Finding finding = finding(0.9);

    assertThat(filter.apply(List.of(finding))).containsExactly(finding);
  }

  @Test
  void mantemAchadoExatamenteNoLimiar() {
    Finding noLimiar = withConfidence(0.6);

    assertThat(filter.apply(List.of(noLimiar))).containsExactly(noLimiar);
  }

  @Test
  void descartaAchadoAbaixoDoLimiar() {
    assertThat(filter.apply(List.of(withConfidence(0.59)))).isEmpty();
  }

  @Test
  void preservaAOrdemEFiltraSomenteOsReprovados() {
    Finding alto = withConfidence(0.95);
    Finding baixo = withConfidence(0.2);
    Finding medio = withConfidence(0.7);

    assertThat(filter.apply(List.of(alto, baixo, medio))).containsExactly(alto, medio);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("achadosInvalidos")
  void descartaAchadoMalFormado(String caso, Finding invalido) {
    assertThat(filter.apply(List.of(invalido))).isEmpty();
  }

  static Object[][] achadosInvalidos() {
    return new Object[][] {
      {"arquivo nulo", new Finding(null, 10, Severity.HIGH, "t", "e", "s", 0.9)},
      {"arquivo em branco", new Finding("  ", 10, Severity.HIGH, "t", "e", "s", 0.9)},
      {"severidade nula", new Finding("a.java", 10, null, "t", "e", "s", 0.9)},
      {"titulo em branco", new Finding("a.java", 10, Severity.HIGH, " ", "e", "s", 0.9)},
      {"titulo nulo", new Finding("a.java", 10, Severity.HIGH, null, "e", "s", 0.9)},
      {"explicacao em branco", new Finding("a.java", 10, Severity.HIGH, "t", "", "s", 0.9)},
      {"explicacao nula", new Finding("a.java", 10, Severity.HIGH, "t", null, "s", 0.9)},
      {"linha zero", new Finding("a.java", 0, Severity.HIGH, "t", "e", "s", 0.9)},
      {"linha negativa", new Finding("a.java", -3, Severity.HIGH, "t", "e", "s", 0.9)},
      {"confianca acima de um", new Finding("a.java", 10, Severity.HIGH, "t", "e", "s", 1.4)},
      {"confianca negativa", new Finding("a.java", 10, Severity.HIGH, "t", "e", "s", -0.2)},
      {"confianca NaN", new Finding("a.java", 10, Severity.HIGH, "t", "e", "s", Double.NaN)},
    };
  }

  @Test
  void sugestaoAusenteNaoDescartaOAchado() {
    // suggestion é opcional: a coluna aceita nulo e nem todo problema tem correção óbvia.
    Finding semSugestao = new Finding("a.java", 10, Severity.MEDIUM, "t", "e", null, 0.8);

    assertThat(filter.apply(List.of(semSugestao))).containsExactly(semSugestao);
  }

  @Test
  void listaNulaOuComEntradaNulaNaoQuebra() {
    // O LLM devolve JSON; uma entrada nula no array é totalmente possível.
    assertThat(filter.apply(null)).isEmpty();
    assertThat(filter.apply(Arrays.asList(finding(0.9), null))).hasSize(1);
  }

  @Test
  void limiarZeroAindaDescartaAchadoMalFormado() {
    // Baixar o limiar não é desligar a validação: achado sem arquivo continua inútil.
    FindingFilter semLimiar = new FindingFilter(0.0);

    assertThat(semLimiar.apply(List.of(withConfidence(0.0)))).hasSize(1);
    assertThat(semLimiar.apply(List.of(new Finding(null, 1, Severity.LOW, "t", "e", "s", 0.0))))
        .isEmpty();
  }

  @Test
  void limiarForaDaFaixaEhRejeitadoNaConstrucao() {
    assertThatThrownBy(() -> new FindingFilter(-0.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FindingFilter(1.1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FindingFilter(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Finding finding(double confidence) {
    return new Finding(
        "src/main/java/App.java",
        42,
        Severity.HIGH,
        "SQL concatenado",
        "A query monta o WHERE com concatenação de string.",
        "Use PreparedStatement com parâmetro.",
        confidence);
  }

  private static Finding withConfidence(double confidence) {
    return finding(confidence);
  }
}
