package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Entrega o installation access token, cacheado por instalação (ADR-006).
 *
 * <p>Sem o cache, cada PR paga duas requisições de autenticação antes de começar a trabalhar e
 * consome rate limit em silêncio. O cache é um {@code ConcurrentHashMap} porque a disputa é entre
 * as poucas threads de consumidor do RabbitMQ — não é um problema de concorrência alta.
 *
 * <p>Nada de credencial em log: o que se registra é o id da instalação e se o token veio do cache.
 */
public class InstallationTokenProvider {

  private static final Logger log = LoggerFactory.getLogger(InstallationTokenProvider.class);

  /** Margem da ADR-006: token que vence no meio da paginação quebra a busca pela metade. */
  private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

  /** Usado só se o GitHub deixar de mandar {@code expires_at}; hoje ele sempre manda. */
  private static final Duration FALLBACK_VALIDITY = Duration.ofMinutes(50);

  private static final String ACCEPT = "application/vnd.github+json";

  private final Map<Long, CachedToken> cache = new ConcurrentHashMap<>();
  private final RestClient restClient;
  private final AppJwtFactory jwtFactory;
  private final Clock clock;

  public InstallationTokenProvider(RestClient restClient, AppJwtFactory jwtFactory, Clock clock) {
    this.restClient = restClient;
    this.jwtFactory = jwtFactory;
    this.clock = clock;
  }

  public String tokenFor(long installationId) {
    Instant now = Instant.now(clock);
    CachedToken cached = cache.get(installationId);

    if (cached != null && cached.usableAt(now)) {
      log.debug("Token da instalação {} veio do cache", installationId);
      return cached.token();
    }

    CachedToken fresh = fetch(installationId, now);
    cache.put(installationId, fresh);
    log.info("Token novo obtido para a instalação {}", installationId);
    return fresh.token();
  }

  private CachedToken fetch(long installationId, Instant now) {
    TokenResponse response =
        restClient
            .post()
            .uri("/app/installations/{installationId}/access_tokens", installationId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtFactory.create())
            .header(HttpHeaders.ACCEPT, ACCEPT)
            .retrieve()
            .body(TokenResponse.class);

    if (response == null || response.token() == null || response.token().isBlank()) {
      throw new IllegalStateException(
          "GitHub respondeu sem token para a instalação " + installationId);
    }
    return new CachedToken(response.token(), expiryOf(response, now));
  }

  private Instant expiryOf(TokenResponse response, Instant now) {
    if (response.expiresAt() == null) {
      return now.plus(FALLBACK_VALIDITY);
    }
    try {
      return Instant.parse(response.expiresAt());
    } catch (DateTimeParseException e) {
      log.warn("expires_at em formato inesperado; usando validade de segurança");
      return now.plus(FALLBACK_VALIDITY);
    }
  }

  private record TokenResponse(String token, @JsonProperty("expires_at") String expiresAt) {}

  private record CachedToken(String token, Instant expiresAt) {

    boolean usableAt(Instant now) {
      return now.isBefore(expiresAt.minus(REFRESH_MARGIN));
    }
  }
}
