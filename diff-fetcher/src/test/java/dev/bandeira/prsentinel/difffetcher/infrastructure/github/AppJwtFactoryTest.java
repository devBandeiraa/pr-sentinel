package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * JWT do GitHub App.
 *
 * <p>O teste verifica a assinatura com a chave pública do par, e não só o formato: assinatura
 * montada à mão que "parece certa" mas não valida é o tipo de erro que só aparece como 401 do
 * GitHub em produção (ADR-006).
 */
class AppJwtFactoryTest {

  private static final String APP_ID = "123456";
  private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

  private static KeyPair keyPair;

  @BeforeAll
  static void generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void geraTresPartesSeparadasPorPonto() {
    String jwt = factory(NOW).create();

    assertThat(jwt.split("\\.")).hasSize(3);
  }

  @Test
  void assinaturaValidaComAChavePublicaDoPar() throws Exception {
    String jwt = factory(NOW).create();
    String[] parts = jwt.split("\\.");

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keyPair.getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));

    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  @Test
  void cabecalhoDeclaraRs256() throws Exception {
    JsonNode header = decode(factory(NOW).create(), 0);

    assertThat(header.path("alg").asText()).isEqualTo("RS256");
    assertThat(header.path("typ").asText()).isEqualTo("JWT");
  }

  @Test
  void issEhOAppIdEIatEhOInstanteAtual() throws Exception {
    JsonNode claims = decode(factory(NOW).create(), 1);

    assertThat(claims.path("iss").asText()).isEqualTo(APP_ID);
    assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
  }

  @Test
  void validadeFicaAbaixoDoTetoDeDezMinutosDoGitHub() throws Exception {
    JsonNode claims = decode(factory(NOW).create(), 1);

    long durationSeconds = claims.path("exp").asLong() - claims.path("iat").asLong();

    assertThat(durationSeconds).isPositive().isLessThan(600);
  }

  @Test
  void naoUsaPaddingNemCaractereForaDoBase64Url() {
    // JWT exige base64url sem padding: '+', '/' e '=' quebram o parse do outro lado.
    String jwt = factory(NOW).create();

    assertThat(jwt).doesNotContain("=").doesNotContain("+").doesNotContain("/");
  }

  @Test
  void doisJwtsEmInstantesDiferentesTemIatDiferente() throws Exception {
    JsonNode primeiro = decode(factory(NOW).create(), 1);
    JsonNode segundo = decode(factory(NOW.plusSeconds(30)).create(), 1);

    assertThat(segundo.path("iat").asLong()).isGreaterThan(primeiro.path("iat").asLong());
  }

  private AppJwtFactory factory(Instant now) {
    return new AppJwtFactory(
        APP_ID, (RSAPrivateKey) keyPair.getPrivate(), Clock.fixed(now, ZoneOffset.UTC));
  }

  private JsonNode decode(String jwt, int part) throws Exception {
    return objectMapper.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[part]));
  }
}
