package dev.bandeira.prsentinel.difffetcher.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Leitura da chave privada do GitHub App.
 *
 * <p>O caso que mais importa aqui é o do erro: o GitHub entrega a chave em PKCS#1 e o Java só lê
 * PKCS#8 (ADR-006). Quem salvar o arquivo como veio precisa receber de volta o comando que resolve,
 * no boot, e não um {@code InvalidKeySpecException} cru no primeiro PR do dia.
 */
class PrivateKeyLoaderTest {

  private static KeyPair keyPair;

  @TempDir Path tempDir;

  @BeforeAll
  static void generateKeyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  @Test
  void leChaveEmPkcs8() throws Exception {
    Path pem = write("chave-pkcs8.pem", pkcs8Pem(keyPair));

    RSAPrivateKey key = PrivateKeyLoader.load(pem);

    assertThat(key.getAlgorithm()).isEqualTo("RSA");
    assertThat(key.getModulus()).isEqualTo(((RSAPrivateKey) keyPair.getPrivate()).getModulus());
  }

  @Test
  void aceitaEspacoEQuebraDeLinhaAoRedorDoConteudo() throws Exception {
    Path pem = write("com-espacos.pem", "\n\n  " + pkcs8Pem(keyPair) + "  \n\n");

    assertThat(PrivateKeyLoader.load(pem)).isNotNull();
  }

  @Test
  void chaveEmPkcs1DizComoConverter() throws Exception {
    Path pem =
        write(
            "chave-pkcs1.pem",
            """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAnaoQuecaDeBase64QueNaoVaiSerLida
            -----END RSA PRIVATE KEY-----
            """);

    assertThatThrownBy(() -> PrivateKeyLoader.load(pem))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PKCS#1")
        .hasMessageContaining("openssl");
  }

  @Test
  void arquivoInexistenteFalhaComOCaminhoNaMensagem() {
    Path ausente = tempDir.resolve("nao-existe.pem");

    assertThatThrownBy(() -> PrivateKeyLoader.load(ausente))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nao-existe.pem");
  }

  @Test
  void conteudoQueNaoEhPemFalhaSemVazarOConteudo() throws Exception {
    Path pem = write("qualquer-coisa.pem", "isto-nao-e-uma-chave-mas-tambem-nao-pode-ir-para-log");

    assertThatThrownBy(() -> PrivateKeyLoader.load(pem))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("isto-nao-e-uma-chave-mas-tambem-nao-pode-ir-para-log");
  }

  @Test
  void mensagemDeErroNuncaCarregaOConteudoDaChave() throws Exception {
    // Chave válida truncada: o parse falha, e o pedaço de chave que sobrou não pode aparecer.
    String base64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    String truncada = base64.substring(0, 200);
    Path pem =
        write(
            "truncada.pem",
            "-----BEGIN PRIVATE KEY-----\n" + truncada + "\n-----END PRIVATE KEY-----\n");

    assertThatThrownBy(() -> PrivateKeyLoader.load(pem))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(truncada.substring(0, 40));
  }

  private Path write(String name, String content) throws Exception {
    Path file = tempDir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  static String pkcs8Pem(KeyPair keyPair) {
    String base64 =
        Base64.getMimeEncoder(64, new byte[] {'\n'})
            .encodeToString(keyPair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
  }
}
