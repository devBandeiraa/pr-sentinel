package dev.bandeira.prsentinel.publisher.infrastructure.github;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Monta o JWT que autentica o App — a primeira das duas etapas da ADR-006.
 *
 * <p>Sem biblioteca de JWT de propósito: o que ela substituiria aqui são duas linhas de JSON e uma
 * assinatura {@code SHA256withRSA}, e o teste verifica a assinatura com a chave pública do par, que
 * é a garantia que importa.
 *
 * <p>O JWT vale 9 minutos. O teto do GitHub é 10, e encostar no teto transforma diferença de
 * relógio entre o container e o GitHub em 401 intermitente.
 *
 * <p><b>Cópia do diff-fetcher</b>, por decisão da ADR-009: com dois consumidores, duplicar sessenta
 * linhas custa menos que fazer o common — dependência dos seis módulos — conhecer HTTP e
 * carregamento de chave privada. O gatilho para extrair é um terceiro consumidor, e o destino será
 * um módulo próprio. Enquanto isso, ajuste no fluxo de token precisa ser feito nos dois lugares, e
 * nada no build avisa se só um foi alterado.
 */
public class AppJwtFactory {

  private static final Duration VALIDITY = Duration.ofMinutes(9);
  private static final String HEADER = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";

  private final String appId;
  private final RSAPrivateKey privateKey;
  private final Clock clock;

  public AppJwtFactory(String appId, RSAPrivateKey privateKey, Clock clock) {
    this.appId = appId;
    this.privateKey = privateKey;
    this.clock = clock;
  }

  public String create() {
    long issuedAt = Instant.now(clock).getEpochSecond();
    long expiresAt = issuedAt + VALIDITY.toSeconds();

    String claims = "{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}".formatted(issuedAt, expiresAt, appId);
    String signingInput = encode(HEADER) + "." + encode(claims);

    return signingInput + "." + base64Url(sign(signingInput));
  }

  private byte[] sign(String signingInput) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(privateKey);
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signature.sign();
    } catch (GeneralSecurityException e) {
      // Sem a causa: a exceção do JCE pode citar material da chave.
      throw new IllegalStateException("Falha ao assinar o JWT do GitHub App");
    }
  }

  private static String encode(String json) {
    return base64Url(json.getBytes(StandardCharsets.UTF_8));
  }

  /** Base64 URL sem padding, como o JWT exige: {@code +}, {@code /} e {@code =} quebram o parse. */
  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
