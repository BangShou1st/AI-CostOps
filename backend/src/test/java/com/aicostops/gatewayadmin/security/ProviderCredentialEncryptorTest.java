package com.aicostops.gatewayadmin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** AIC-095 authenticated-encryption boundary for Provider credentials. */
class ProviderCredentialEncryptorTest {

    private static final String KEK =
            Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEK =
            Base64.getEncoder().encodeToString(secretBytes(1));

    @Test
    void roundTripPreservesRawProviderSecret() {
        var encryptor = new ProviderCredentialEncryptor(KEK);
        var encrypted = encryptor.encrypt("sk-mimo-test-secret", 11L, 22L, "API_KEY", (short) 1);

        assertThat(encryptor.decrypt(encrypted.ciphertext(), encrypted.nonce(),
                11L, 22L, "API_KEY", (short) 1)).isEqualTo("sk-mimo-test-secret");
    }

    @Test
    void tamperedCiphertextFailsDecryption() {
        var encryptor = new ProviderCredentialEncryptor(KEK);
        var encrypted = encryptor.encrypt("sk-mimo-test-secret", 11L, 22L, "API_KEY", (short) 1);
        var tampered = encrypted.ciphertext().clone();
        tampered[0] = (byte) (tampered[0] ^ 0x01);

        assertThatThrownBy(() -> encryptor.decrypt(tampered, encrypted.nonce(),
                11L, 22L, "API_KEY", (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongOrganizationInAadFailsDecryption() {
        var encryptor = new ProviderCredentialEncryptor(KEK);
        var encrypted = encryptor.encrypt("sk-mimo-test-secret", 11L, 22L, "API_KEY", (short) 1);

        assertThatThrownBy(() -> encryptor.decrypt(encrypted.ciphertext(), encrypted.nonce(),
                99L, 22L, "API_KEY", (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongProviderAccountInAadFailsDecryption() {
        var encryptor = new ProviderCredentialEncryptor(KEK);
        var encrypted = encryptor.encrypt("sk-mimo-test-secret", 11L, 22L, "API_KEY", (short) 1);

        assertThatThrownBy(() -> encryptor.decrypt(encrypted.ciphertext(), encrypted.nonce(),
                11L, 77L, "API_KEY", (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongKeyFailsDecryption() {
        var first = new ProviderCredentialEncryptor(KEK);
        var encrypted = first.encrypt("sk-mimo-test-secret", 11L, 22L, "API_KEY", (short) 1);
        var second = new ProviderCredentialEncryptor(OTHER_KEK);

        assertThatThrownBy(() -> second.decrypt(encrypted.ciphertext(), encrypted.nonce(),
                11L, 22L, "API_KEY", (short) 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonceIsTwelveBytesAndCiphertextIncludesGcmTag() {
        var encryptor = new ProviderCredentialEncryptor(KEK);
        var raw = "sk-mimo-test-secret";
        var encrypted = encryptor.encrypt(raw, 11L, 22L, "API_KEY", (short) 1);

        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext().length).isEqualTo(raw.getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(new String(encrypted.ciphertext(), StandardCharsets.UTF_8)).doesNotContain(raw);
    }

    @Test
    void blankKekRejected() {
        assertThatThrownBy(() -> new ProviderCredentialEncryptor("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_PROVIDER_KEK_V1");
    }

    @Test
    void malformedBase64KekRejected() {
        assertThatThrownBy(() -> new ProviderCredentialEncryptor("not-base64!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_PROVIDER_KEK_V1");
    }

    @Test
    void wrongLengthKekRejected() {
        assertThatThrownBy(() -> new ProviderCredentialEncryptor(
                Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_PROVIDER_KEK_V1");
    }

    private static byte[] secretBytes(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}