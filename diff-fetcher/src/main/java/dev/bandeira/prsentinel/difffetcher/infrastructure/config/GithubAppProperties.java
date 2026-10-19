package dev.bandeira.prsentinel.difffetcher.infrastructure.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identidade do GitHub App.
 *
 * @param appId id numérico do App, que vai no {@code iss} do JWT
 * @param privateKeyPath caminho da chave privada em PKCS#8, montada como secret do Docker. É
 *     caminho, e não o conteúdo da chave, de propósito: chave em variável de ambiente vaza em
 *     {@code docker inspect}, em log de crash e em dump de ambiente (ADR-006).
 * @param apiBaseUrl endereço da API, parametrizado para o GitHub Enterprise e para os testes
 */
@ConfigurationProperties(prefix = "github")
public record GithubAppProperties(String appId, Path privateKeyPath, String apiBaseUrl) {

  public GithubAppProperties {
    apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "https://api.github.com";
  }
}
