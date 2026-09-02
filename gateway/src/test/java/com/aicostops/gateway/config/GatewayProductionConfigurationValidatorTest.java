package com.aicostops.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.provider.mimo.MimoEndpointPolicy;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * AIC-091 production fail-fast: malformed/missing Base64 32-byte HMAC/KEK
 * secrets, dev bootstrap and dev raw keys reject startup, and the Provider
 * boundary rejects non-HTTPS/non-approved MiMo endpoints in production.
 */
class GatewayProductionConfigurationValidatorTest {

    private static final String VALID_32B_BASE64 = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Test
    void validSecretsAndSafeDevFlagsPass() {
        var properties = validProperties();
        var validator = new GatewayProductionConfigurationValidator(properties);

        validator.validate();
    }

    @Test
    void missingCredentialHmacKeyRejects() {
        var properties = validProperties();
        properties.setCredentialHmacKeyV1("");

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credential-hmac-key-v1");
    }

    @Test
    void missingRequestHmacKeyRejects() {
        var properties = validProperties();
        properties.setRequestHmacKeyV1(null);

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request-hmac-key-v1");
    }

    @Test
    void missingProviderKekRejects() {
        var properties = validProperties();
        properties.setProviderKekV1("   ");

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider-kek-v1");
    }

    @Test
    void malformedBase64Rejects() {
        var properties = validProperties();
        properties.setCredentialHmacKeyV1("not-base64!!!");

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be valid Base64");
    }

    @Test
    void non32ByteSecretRejects() {
        var properties = validProperties();
        properties.setCredentialHmacKeyV1(
                Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes, got 16");
    }

    @Test
    void devBootstrapEnabledRejects() {
        var properties = validProperties();
        properties.setDevBootstrapEnabled(true);

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-bootstrap-enabled");
    }

    @Test
    void devRawKeySetRejects() {
        var properties = validProperties();
        properties.setDevRawKey("aic_0123456789ab_" + "A".repeat(43));

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-raw-key");
    }

    @Test
    void invalidResourceLimitRejects() {
        var properties = validProperties();
        properties.setMaxActiveStreams(0);

        assertThatThrownBy(() -> new GatewayProductionConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxActiveStreams");
    }

    @Test
    void providerEndpointPolicyRequiresHttpsAndApprovedHost() {
        assertThatThrownBy(() -> MimoEndpointPolicy.validate("http://api.xiaomimimo.com/v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> MimoEndpointPolicy.validate("https://evil.example.com/v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not approved");
        assertThatThrownBy(() -> MimoEndpointPolicy.validate("not a url"))
                .isInstanceOf(IllegalStateException.class);

        MimoEndpointPolicy.validate("https://api.xiaomimimo.com/v1");
    }

    @Test
    void requiredSecretIsStaticAndReusable() {
        GatewayProductionConfigurationValidator.requireSecret("x", VALID_32B_BASE64);
    }

    private static GatewayProperties validProperties() {
        var properties = new GatewayProperties();
        properties.setCredentialHmacKeyV1(VALID_32B_BASE64);
        properties.setRequestHmacKeyV1(VALID_32B_BASE64);
        properties.setProviderKekV1(VALID_32B_BASE64);
        properties.setDevBootstrapEnabled(false);
        properties.setDevRawKey("");
        return properties;
    }
}