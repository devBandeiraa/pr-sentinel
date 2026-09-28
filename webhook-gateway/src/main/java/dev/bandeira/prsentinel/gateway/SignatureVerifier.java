package dev.bandeira.prsentinel.gateway;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Valida o header X-Hub-Signature-256 enviado pelo GitHub.
 *
 * <p>A comparação usa MessageDigest.isEqual (tempo constante) para não vazar, via timing attack,
 * quantos bytes da assinatura estavam corretos.
 */
@Component
public class SignatureVerifier {

  private static final String PREFIX = "sha256=";
  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] secret;

  public SignatureVerifier(WebhookProperties properties) {
    this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
  }

  public boolean isValid(byte[] payload, String signatureHeader) {
    if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
      return false;
    }
    byte[] expected = hmac(payload);
    byte[] received;
    try {
      received = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return false;
    }
    return MessageDigest.isEqual(expected, received);
  }

  private byte[] hmac(byte[] payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return mac.doFinal(payload);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Falha ao calcular HMAC", e);
    }
  }
}
