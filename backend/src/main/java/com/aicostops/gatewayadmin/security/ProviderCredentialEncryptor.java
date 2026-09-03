package com.aicostops.gatewayadmin.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated-encryption boundary for Provider credentials at rest.
 *
 * <p>AES-256-GCM with a 12-byte random nonce and 128-bit tag. The key is
 * Base64 of exactly 32 bytes from {@code AICOSTOPS_PROVIDER_KEK_V1}. The AAD
 * binds every ciphertext to its org / provider account / credential type /
 * key version so a forged or misplaced ciphertext cannot be decrypted under a
 * different context. The stored ciphertext is the JCE ciphertext plus the
 * 16-byte GCM tag; the raw secret is never persisted or logged.
 */
public final class ProviderCredentialEncryptor {

    public static final String AAD_DOMAIN = "aicostops:v2:provider-credential:v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final byte[] kek;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProviderCredentialEncryptor(String kekBase64) {
        this.kek = GatewayKeyCodec.decode32ByteKey("AICOSTOPS_PROVIDER_KEK_V1", kekBase64);
    }

    public EncryptedProviderSecret encrypt(
            String rawProviderSecret,
            long orgId,
            long providerAccountId,
            String credentialType,
            short encryptionKeyVersion) {
        Objects.requireNonNull(rawProviderSecret, "rawProviderSecret must not be null");
        var nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        var cipher = cipher(Cipher.ENCRYPT_MODE, nonce,
                aad(orgId, providerAccountId, credentialType, encryptionKeyVersion));
        try {
            var ciphertext = cipher.doFinal(rawProviderSecret.getBytes(StandardCharsets.UTF_8));
            return new EncryptedProviderSecret(ciphertext, nonce, encryptionKeyVersion);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Provider credential encryption failed", ex);
        }
    }

    public String decrypt(
            byte[] ciphertext,
            byte[] nonce,
            long orgId,
            long providerAccountId,
            String credentialType,
            short encryptionKeyVersion) {
        var cipher = cipher(Cipher.DECRYPT_MODE, nonce,
                aad(orgId, providerAccountId, credentialType, encryptionKeyVersion));
        try {
            var plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException(
                    "Provider credential decryption failed (tampered ciphertext, wrong key or wrong AAD)", ex);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, byte[] aad) {
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM provider credential cipher is unavailable", ex);
        }
    }

    private static byte[] aad(long orgId, long providerAccountId, String credentialType,
            short encryptionKeyVersion) {
        return (AAD_DOMAIN + "\0" + orgId + "\0" + providerAccountId + "\0"
                + credentialType + "\0" + encryptionKeyVersion).getBytes(StandardCharsets.UTF_8);
    }

    /** Durable encrypted-at-rest representation: JCE ciphertext + tag and nonce. */
    public record EncryptedProviderSecret(byte[] ciphertext, byte[] nonce, short encryptionKeyVersion) {
    }
}