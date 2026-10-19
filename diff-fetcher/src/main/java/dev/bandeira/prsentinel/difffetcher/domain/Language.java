package dev.bandeira.prsentinel.difffetcher.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Linguagem do arquivo, deduzida da extensão.
 *
 * <p>O valor vai no prompt do agente como contexto ("você está revisando Java"). É dica, não
 * requisito: extensão desconhecida devolve {@code text} e o arquivo continua sendo revisado
 * (ADR-007).
 */
public final class Language {

  private static final String DEFAULT = "text";

  private static final Map<String, String> BY_EXTENSION =
      Map.ofEntries(
          Map.entry("java", "java"),
          Map.entry("kt", "kotlin"),
          Map.entry("kts", "kotlin"),
          Map.entry("groovy", "groovy"),
          Map.entry("gradle", "groovy"),
          Map.entry("scala", "scala"),
          Map.entry("py", "python"),
          Map.entry("rb", "ruby"),
          Map.entry("go", "go"),
          Map.entry("rs", "rust"),
          Map.entry("cs", "csharp"),
          Map.entry("php", "php"),
          Map.entry("swift", "swift"),
          Map.entry("c", "c"),
          Map.entry("h", "c"),
          Map.entry("cpp", "cpp"),
          Map.entry("hpp", "cpp"),
          Map.entry("js", "javascript"),
          Map.entry("jsx", "javascript"),
          Map.entry("mjs", "javascript"),
          Map.entry("ts", "typescript"),
          Map.entry("tsx", "typescript"),
          Map.entry("html", "html"),
          Map.entry("css", "css"),
          Map.entry("scss", "scss"),
          Map.entry("sql", "sql"),
          Map.entry("sh", "shell"),
          Map.entry("bash", "shell"),
          Map.entry("xml", "xml"),
          Map.entry("json", "json"),
          Map.entry("yml", "yaml"),
          Map.entry("yaml", "yaml"),
          Map.entry("toml", "toml"),
          Map.entry("tf", "terraform"),
          Map.entry("properties", "properties"),
          Map.entry("md", "markdown"),
          Map.entry("dockerfile", "dockerfile"));

  /** Arquivos reconhecidos pelo nome, porque não têm extensão. */
  private static final Map<String, String> BY_FILENAME =
      Map.of("dockerfile", "dockerfile", "makefile", "makefile", "jenkinsfile", "groovy");

  private Language() {}

  public static String of(String path) {
    if (path == null || path.isBlank()) {
      return DEFAULT;
    }

    // Só o nome do arquivo interessa: um ponto no nome do diretório não é extensão.
    String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);

    String byName = BY_FILENAME.get(fileName);
    if (byName != null) {
      return byName;
    }

    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return DEFAULT;
    }
    return BY_EXTENSION.getOrDefault(fileName.substring(dot + 1), DEFAULT);
  }
}
