package dev.bandeira.prsentinel.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    // Exemplo oficial da documentação do GitHub
    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String VALID_SIGNATURE =
            "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    private final SignatureVerifier verifier = new SignatureVerifier(new WebhookProperties(SECRET));

    @Test
    void aceitaAssinaturaValida() {
        assertThat(verifier.isValid(PAYLOAD, VALID_SIGNATURE)).isTrue();
    }

    @Test
    void rejeitaPayloadAlterado() {
        byte[] tampered = "Hello, World?".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.isValid(tampered, VALID_SIGNATURE)).isFalse();
    }

    @Test
    void rejeitaHeaderAusenteOuMalformado() {
        assertThat(verifier.isValid(PAYLOAD, null)).isFalse();
        assertThat(verifier.isValid(PAYLOAD, "sha1=abc")).isFalse();
        assertThat(verifier.isValid(PAYLOAD, "sha256=nao-e-hex")).isFalse();
    }
}
