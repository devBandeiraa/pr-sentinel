package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Cache do installation token.
 *
 * <p>Usa {@link MockRestServiceServer} em vez de WireMock: o adaptador é um {@link RestClient}, e o
 * próprio spring-test já intercepta a chamada — não valeu puxar uma dependência nova só para
 * responder dois JSONs.
 *
 * <p>O que estes testes prendem é a contagem de requisições. Cache que não cacheia não quebra nada
 * visível: só gasta duas chamadas de API por PR e consome o rate limit em silêncio (ADR-006).
 */
class InstallationTokenProviderTest {

  private static final String BASE_URL = "https://api.github.com";
  private static final long INSTALLATION_ID = 42L;
  private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

  private static KeyPair keyPair;

  @BeforeAll
  static void generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  private final MutableClock clock = new MutableClock(NOW);
  private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final AppJwtFactory jwtFactory =
      new AppJwtFactory("123456", (RSAPrivateKey) keyPair.getPrivate(), clock);
  private final InstallationTokenProvider provider =
      new InstallationTokenProvider(builder.build(), jwtFactory, clock);

  @Test
  void buscaOTokenENaoPedeDeNovoEnquantoEleVale() {
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(tokenResponse("ghs-token", NOW.plus(Duration.ofHours(1))));

    assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs-token");
    assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs-token");

    server.verify();
  }

  @Test
  void autenticaAChamadaComOJwtDoApp() {
    // O JWT é determinístico para um relógio fixo (RSASSA-PKCS1-v1_5), então dá para prender o
    // header exato em vez de só checar que existe algo ali.
    String expectedJwt =
        new AppJwtFactory(
                "123456", (RSAPrivateKey) keyPair.getPrivate(), Clock.fixed(NOW, ZoneOffset.UTC))
            .create();

    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andExpect(header("Authorization", "Bearer " + expectedJwt))
        .andExpect(header("Accept", "application/vnd.github+json"))
        .andRespond(tokenResponse("ghs-token", NOW.plus(Duration.ofHours(1))));

    provider.tokenFor(INSTALLATION_ID);

    server.verify();
  }

  @Test
  void renovaQuandoFaltaMenosDeCincoMinutosParaExpirar() {
    // Margem da ADR-006: token que vence no meio da paginação do diff quebra a busca pela metade.
    server
        .expect(times(2), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(tokenResponse("ghs-quase-vencido", NOW.plus(Duration.ofMinutes(4))));

    provider.tokenFor(INSTALLATION_ID);
    provider.tokenFor(INSTALLATION_ID);

    server.verify();
  }

  @Test
  void naoRenovaEnquantoAMargemNaoEhAlcancada() {
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(tokenResponse("ghs-token", NOW.plus(Duration.ofHours(1))));

    provider.tokenFor(INSTALLATION_ID);
    clock.advance(Duration.ofMinutes(50));
    provider.tokenFor(INSTALLATION_ID);

    server.verify();
  }

  @Test
  void renovaDepoisQueOTokenVence() {
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(tokenResponse("ghs-primeiro", NOW.plus(Duration.ofHours(1))));
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(tokenResponse("ghs-segundo", NOW.plus(Duration.ofHours(2))));

    assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs-primeiro");
    clock.advance(Duration.ofHours(1));
    assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs-segundo");

    server.verify();
  }

  @Test
  void cacheEhSeparadoPorInstalacao() {
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(tokenResponse("ghs-quarenta-e-dois", NOW.plus(Duration.ofHours(1))));
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/99/access_tokens"))
        .andRespond(tokenResponse("ghs-noventa-e-nove", NOW.plus(Duration.ofHours(1))));

    assertThat(provider.tokenFor(42L)).isEqualTo("ghs-quarenta-e-dois");
    assertThat(provider.tokenFor(99L)).isEqualTo("ghs-noventa-e-nove");
    assertThat(provider.tokenFor(42L)).isEqualTo("ghs-quarenta-e-dois");

    server.verify();
  }

  @Test
  void respostaSemTokenFalha() {
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(
            withSuccess("{\"expires_at\":\"2026-10-19T13:00:00Z\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> provider.tokenFor(INSTALLATION_ID))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void erroDoGitHubFalhaSemVazarCredencialNaMensagem() {
    server
        .expect(times(1), requestTo(BASE_URL + "/app/installations/42/access_tokens"))
        .andRespond(withUnauthorizedRequest());

    assertThatThrownBy(() -> provider.tokenFor(INSTALLATION_ID))
        .satisfies(
            e ->
                assertThat(String.valueOf(e.getMessage()))
                    .doesNotContain("Bearer")
                    .doesNotContain("BEGIN"));
  }

  private static org.springframework.test.web.client.response.DefaultResponseCreator tokenResponse(
      String token, Instant expiresAt) {
    String body = "{\"token\":\"%s\",\"expires_at\":\"%s\"}".formatted(token, expiresAt);
    return withSuccess(body, MediaType.APPLICATION_JSON);
  }

  /** Relógio que o teste empurra para frente, para exercitar a expiração sem esperar de verdade. */
  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      this.now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
