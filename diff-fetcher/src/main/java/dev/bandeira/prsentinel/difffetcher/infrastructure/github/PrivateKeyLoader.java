package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Lê a chave privada do GitHub App de um arquivo PEM em PKCS#8.
 *
 * <p>O GitHub entrega a chave em PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) e o Java lê PKCS#8. A
 * conversão é passo de configuração, documentado no README, e não dependência de biblioteca
 * (ADR-006). Quando o arquivo vem no formato errado, a mensagem traz o comando que resolve.
 *
 * <p>Nenhuma mensagem de erro carrega conteúdo do arquivo — nem um pedaço. Chave privada em log é
 * chave privada comprometida.
 */
public final class PrivateKeyLoader {

  private static final String PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----";
  private static final String PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----";
  private static final String PKCS8_FOOTER = "-----END PRIVATE KEY-----";

  private static final String PKCS1_HELP =
      """
      A chave do GitHub App está em PKCS#1, que o Java não lê. Converta uma vez:
        openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \\
          -in <chave-baixada>.pem -out secrets/github-app.pem""";

  private PrivateKeyLoader() {}

  public static RSAPrivateKey load(Path path) {
    String pem = read(path);

    if (pem.contains(PKCS1_HEADER)) {
      throw new IllegalStateException(PKCS1_HELP);
    }

    String base64 = pem.replace(PKCS8_HEADER, "").replace(PKCS8_FOOTER, "").replaceAll("\\s", "");
    try {
      PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
      return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
    } catch (RuntimeException | java.security.GeneralSecurityException e) {
      // Sem a causa e sem o conteúdo: a exceção do JCE pode trazer bytes da chave.
      throw new IllegalStateException(
          "Chave privada do GitHub App inválida em " + path + ". Esperado PEM em PKCS#8.");
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path).trim();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Chave privada do GitHub App não pôde ser lida em " + path, e);
    }
  }
}
