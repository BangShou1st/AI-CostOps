package com.aicostops.gateway.provider;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Decrypts Provider credentials at the narrow Provider call boundary using
 * the same versioned AES-256-GCM + AAD contract as the Control Plane
 * encryptor ({@code aicostops:v2:provider-credential:v1}).
 */
@Component
public class ProviderCredentialDecryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final String AAD_DOMAIN = "aicostops:v2:provider-credential:v1";

    private final GatewayReadMapper readMapper;
    private final byte[] kek;

    public ProviderCredentialDecryptor(GatewayReadMapper readMapper, GatewayProperties properties) {
        this.readMapper = readMapper;
        var base64 = properties.getProviderKekV1() == null ? "" : properties.getProviderKekV1().trim();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("AICOSTOPS_PROVIDER_KEK_V1 must be valid Base64", ex);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("AICOSTOPS_PROVIDER_KEK_V1 must decode to exactly 32 bytes");
        }
        this.kek = decoded;
    }

    public DecryptedCredential decrypt(long orgId, long providerAccountId) {
        var row = readMapper.findActiveProviderCredential(orgId, providerAccountId);
        if (row == null) {
            throw new IllegalStateException("No ACTIVE Provider credential for account " + providerAccountId);
        }
        var aad = (AAD_DOMAIN + "\0" + orgId + "\0" + providerAccountId + "\0" + row.credentialType() + "\0"
                + row.encryptionKeyVersion()).getBytes(StandardCharsets.UTF_8);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, row.nonce()));
            cipher.updateAAD(aad);
            return new DecryptedCredential(row.credentialType(), cipher.doFinal(row.ciphertext()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Provider credential decryption failed", ex);
        }
    }

    public record DecryptedCredential(String credentialType, byte[] secret) {
    }
}
