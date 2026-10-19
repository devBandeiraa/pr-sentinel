package dev.bandeira.prsentinel.difffetcher.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Linguagem a partir da extensão.
 *
 * <p>O valor vai no prompt do agente como dica de contexto, então extensão desconhecida devolve
 * {@code text} em vez de falhar: não saber a linguagem não é motivo para deixar de revisar o
 * arquivo (ADR-007).
 */
class LanguageTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "src/main/java/App.java, java",
    "app/main.py, python",
    "web/index.ts, typescript",
    "web/app.tsx, typescript",
    "web/app.js, javascript",
    "cmd/main.go, go",
    "lib/util.rb, ruby",
    "src/lib.rs, rust",
    "Program.cs, csharp",
    "src/main.kt, kotlin",
    "query.sql, sql",
    "pom.xml, xml",
    "config.yml, yaml",
    "config.yaml, yaml",
    "package.json, json",
    "Dockerfile, dockerfile",
    "script.sh, shell",
    "README.md, markdown"
  })
  void mapeiaAExtensaoParaALinguagem(String path, String expected) {
    assertThat(Language.of(path)).isEqualTo(expected);
  }

  @Test
  void extensaoDesconhecidaViraText() {
    assertThat(Language.of("algo.xyz")).isEqualTo("text");
  }

  @Test
  void arquivoSemExtensaoViraText() {
    assertThat(Language.of("LICENSE")).isEqualTo("text");
  }

  @Test
  void extensaoEhReconhecidaIndependenteDeCaixa() {
    assertThat(Language.of("src/App.JAVA")).isEqualTo("java");
  }

  @Test
  void caminhoNuloOuVazioViraText() {
    assertThat(Language.of(null)).isEqualTo("text");
    assertThat(Language.of("")).isEqualTo("text");
  }

  @Test
  void pontoNoNomeDoDiretorioNaoConfundeADeteccao() {
    // O ponto do diretório vem antes da última barra: não é extensão.
    assertThat(Language.of("src/com.example.app/Main")).isEqualTo("text");
  }
}
