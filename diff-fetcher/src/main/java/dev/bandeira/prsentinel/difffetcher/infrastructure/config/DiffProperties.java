package dev.bandeira.prsentinel.difffetcher.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Como o diff é filtrado e fatiado (ADR-007).
 *
 * @param maxChunkChars tamanho máximo de um chunk. O limite real é de tokens, e caractere aproxima
 *     token muito melhor que linha — linha de diff vai de três a trezentos caracteres.
 * @param ignoredPatterns globs de arquivos que não valem review: lockfile, gerado, minificado
 */
@ConfigurationProperties(prefix = "diff")
public record DiffProperties(int maxChunkChars, List<String> ignoredPatterns) {

  public DiffProperties {
    maxChunkChars = maxChunkChars > 0 ? maxChunkChars : 12_000;
    ignoredPatterns = ignoredPatterns != null ? List.copyOf(ignoredPatterns) : List.of();
  }
}
