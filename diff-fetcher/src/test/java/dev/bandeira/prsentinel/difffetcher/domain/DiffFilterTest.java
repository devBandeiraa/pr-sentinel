package dev.bandeira.prsentinel.difffetcher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Quais arquivos do PR valem uma review.
 *
 * <p>Cada arquivo que passa por aqui vira chunk, e cada chunk custa três chamadas de LLM pelo
 * fan-out da ADR-001. Filtrar é o que mantém o custo proporcional ao código que importa (ADR-007).
 */
class DiffFilterTest {

  private static final List<String> PATTERNS =
      List.of("**/package-lock.json", "**/yarn.lock", "**/*.min.js", "**/generated/**");

  private final DiffFilter filter = new DiffFilter(PATTERNS);

  @Test
  void arquivoDeCodigoComPatchEhRevisavel() {
    assertThat(filter.isReviewable(modified("src/main/java/App.java", "@@ -1 +1 @@\n+ok")))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "package-lock.json",
        "web/package-lock.json",
        "web/nested/deep/package-lock.json",
        "yarn.lock",
        "web/yarn.lock",
        "static/app.min.js",
        "web/assets/bundle.min.js",
        "src/generated/Api.java",
        "build/generated/deep/Thing.java"
      })
  void arquivoQueCasaComUmPadraoIgnoradoNaoEhRevisavel(String path) {
    assertThat(filter.isReviewable(modified(path, "@@ -1 +1 @@\n+ok"))).isFalse();
  }

  @Test
  void arquivoSemPatchNaoEhRevisavel() {
    // Binário ou grande demais: o GitHub omite o campo patch e não há texto para revisar.
    assertThat(filter.isReviewable(new ChangedFile("docs/logo.png", FileStatus.ADDED, null)))
        .isFalse();
    assertThat(filter.isReviewable(new ChangedFile("docs/logo.png", FileStatus.ADDED, "  ")))
        .isFalse();
  }

  @Test
  void arquivoRemovidoNaoEhRevisavel() {
    // Achado sobre linha que deixou de existir é ruído.
    assertThat(
            filter.isReviewable(
                new ChangedFile("src/Velho.java", FileStatus.REMOVED, "@@ -1 +0,0 @@\n-tchau")))
        .isFalse();
  }

  @Test
  void arquivoAdicionadoRenomeadoOuAlteradoEhRevisavel() {
    String patch = "@@ -1 +1 @@\n+ok";

    assertThat(filter.isReviewable(new ChangedFile("a.java", FileStatus.ADDED, patch))).isTrue();
    assertThat(filter.isReviewable(new ChangedFile("b.java", FileStatus.MODIFIED, patch))).isTrue();
    assertThat(filter.isReviewable(new ChangedFile("c.java", FileStatus.RENAMED, patch))).isTrue();
  }

  @Test
  void semPadraoConfiguradoTudoQueTemPatchPassa() {
    DiffFilter semPadroes = new DiffFilter(List.of());

    assertThat(semPadroes.isReviewable(modified("package-lock.json", "@@ -1 +1 @@\n+ok"))).isTrue();
  }

  @Test
  void padraoSemCuringaCasaOCaminhoExato() {
    DiffFilter exato = new DiffFilter(List.of("docs/CHANGELOG.md"));

    assertThat(exato.isReviewable(modified("docs/CHANGELOG.md", "@@ -1 +1 @@\n+ok"))).isFalse();
    assertThat(exato.isReviewable(modified("docs/README.md", "@@ -1 +1 @@\n+ok"))).isTrue();
  }

  @Test
  void padraoInvalidoNaoDerrubaOFiltro() {
    // Padrão vem de configuração: um glob malformado não pode impedir a review de um PR inteiro.
    DiffFilter comPadraoRuim = new DiffFilter(List.of("[", "**/*.min.js"));

    assertThat(comPadraoRuim.isReviewable(modified("src/App.java", "@@ -1 +1 @@\n+ok"))).isTrue();
    assertThat(comPadraoRuim.isReviewable(modified("a.min.js", "@@ -1 +1 @@\n+ok"))).isFalse();
  }

  private static ChangedFile modified(String path, String patch) {
    return new ChangedFile(path, FileStatus.MODIFIED, patch);
  }
}
