package dev.bandeira.prsentinel.publisher.infrastructure.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identidade do GitHub App, igual à do diff-fetcher (ADR-006).
 *
 * @param appId id numérico do App, que vai no {@code iss} do JWT
 * @param privateKeyPath caminho da chave em PKCS#8, montada como secret do Docker — nunca o
 *     conteúdo da chave em variável de ambiente
 * @param apiBaseUrl endereço da API, parametrizado para o GitHub Enterprise e para os testes
 */
@ConfigurationProperties(prefix = "github")
public record GithubAppProperties(String appId, Path privateKeyPath, String apiBaseUrl) {

  public GithubAppProperties {
    apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "https://api.github.com";
  }
}
