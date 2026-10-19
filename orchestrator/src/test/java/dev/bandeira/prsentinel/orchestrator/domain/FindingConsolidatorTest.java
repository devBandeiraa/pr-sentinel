package dev.bandeira.prsentinel.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.bandeira.prsentinel.common.event.Finding;
import dev.bandeira.prsentinel.common.event.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Consolidação dos achados dos três agentes (ADR-008).
 *
 * <p>Os focos dos agentes se sobrepõem: uma query montada por concatenação é SQL injection para o
 * agente de segurança e violação de camada para o de padrões. Sem deduplicar, o autor do PR recebe
 * dois comentários na mesma linha dizendo coisas parecidas.
 */
class FindingConsolidatorTest {

  private final FindingConsolidator consolidator = new FindingConsolidator();

  @Test
  void naMesmaLinhaFicaOAchadoMaisSevero() {
    Finding critico = finding("Login.java", 11, Severity.CRITICAL, 0.7);
    Finding medio = finding("Login.java", 11, Severity.MEDIUM, 0.95);

    assertThat(consolidator.consolidate(List.of(medio, critico))).containsExactly(critico);
  }

  @Test
  void empateDeSeveridadeDecidePelaConfianca() {
    Finding confiante = finding("Login.java", 11, Severity.HIGH, 0.95);
    Finding duvidoso = finding("Login.java", 11, Severity.HIGH, 0.65);

    assertThat(consolidator.consolidate(List.of(duvidoso, confiante))).containsExactly(confiante);
  }

  @Test
  void empateTotalMantemOPrimeiroQueChegou() {
    Finding primeiro = new Finding("a.java", 5, Severity.HIGH, "primeiro", "e", "s", 0.8);
    Finding segundo = new Finding("a.java", 5, Severity.HIGH, "segundo", "e", "s", 0.8);

    assertThat(consolidator.consolidate(List.of(primeiro, segundo))).containsExactly(primeiro);
  }

  @Test
  void linhasDiferentesDoMesmoArquivoSobrevivemAsDuas() {
    Finding linha10 = finding("a.java", 10, Severity.HIGH, 0.9);
    Finding linha20 = finding("a.java", 20, Severity.HIGH, 0.9);

    assertThat(consolidator.consolidate(List.of(linha10, linha20)))
        .containsExactly(linha10, linha20);
  }

  @Test
  void mesmaLinhaEmArquivosDiferentesSobrevivemAsDuas() {
    Finding emA = finding("a.java", 10, Severity.HIGH, 0.9);
    Finding emB = finding("b.java", 10, Severity.HIGH, 0.9);

    assertThat(consolidator.consolidate(List.of(emA, emB))).containsExactly(emA, emB);
  }

  @Test
  void ordenaPorSeveridadeDepoisArquivoDepoisLinha() {
    // O comentário da fase 6 começa pelo que importa: o mais grave primeiro.
    Finding info = finding("a.java", 1, Severity.INFO, 0.9);
    Finding criticoEmB = finding("b.java", 50, Severity.CRITICAL, 0.9);
    Finding criticoEmAlinha9 = finding("a.java", 9, Severity.CRITICAL, 0.9);
    Finding criticoEmAlinha2 = finding("a.java", 2, Severity.CRITICAL, 0.9);
    Finding medio = finding("a.java", 3, Severity.MEDIUM, 0.9);

    assertThat(
            consolidator.consolidate(
                List.of(info, criticoEmB, criticoEmAlinha9, medio, criticoEmAlinha2)))
        .containsExactly(criticoEmAlinha2, criticoEmAlinha9, criticoEmB, medio, info);
  }

  @Test
  void listaVaziaOuNulaDevolveVazia() {
    assertThat(consolidator.consolidate(List.of())).isEmpty();
    assertThat(consolidator.consolidate(null)).isEmpty();
  }

  @Test
  void tresAgentesNaMesmaLinhaDeixamUmAchadoSo() {
    Finding seguranca = finding("Login.java", 11, Severity.CRITICAL, 0.95);
    Finding performance = finding("Login.java", 11, Severity.LOW, 0.8);
    Finding padroes = finding("Login.java", 11, Severity.MEDIUM, 0.7);

    assertThat(consolidator.consolidate(List.of(seguranca, performance, padroes)))
        .containsExactly(seguranca);
  }

  private static Finding finding(String file, int line, Severity severity, double confidence) {
    return new Finding(
        file, line, severity, "título de " + severity, "explicação", "sugestão", confidence);
  }
}
