package com.aicostops.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Production fail-fast validation: the {@code prod} profile must reject
 * dev-only, weak, or unsafe runtime configuration before the application
 * becomes ready. Validation reads the resolved Spring properties (the same
 * names operators set through the environment), and every error message names
 * the environment variable without printing its value.
 */
class ProductionConfigurationValidatorTest {

    private static final String STRONG_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String M11_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Test
    void blankJwtSigningKeyRejectsStartup() {
        var env = prod();
        env.setProperty("aicostops.auth.jwt-signing-secret", "  ");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY");
    }

    @Test
    void shortJwtSigningKeyRejectsStartup() {
        var env = prod();
        env.setProperty("aicostops.auth.jwt-signing-secret", "short-key");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY");
    }

    @Test
    void devBootstrapEnabledRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.dev-bootstrap-enabled", "true");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_DEV_BOOTSTRAP_ENABLED");
    }

    @Test
    void m11GatewayDevBootstrapEnabledRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.dev-bootstrap-enabled", "true");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED");
    }

    @Test
    void missingM11GatewayKeysRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.provider-kek-v1", "  ");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_PROVIDER_KEK_V1");
    }

    @Test
    void malformedBase64M11KeyRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.credential-hmac-key-v1", "not-base64!!");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1");
    }

    @Test
    void wrongLengthM11KeyRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.request-hmac-key-v1", "c2hvcnQ=");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1");
    }

    @Test
    void publicRegistrationEnabledRejectsStartupWithoutExplicitAllowPolicy() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allow-public-registration", "true");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_ALLOW_PUBLIC_REGISTRATION");
    }

    @Test
    void insecureRefreshCookieRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.refresh-cookie-secure", "false");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_REFRESH_COOKIE_SECURE");
    }

    @Test
    void fileBackedDevMailboxEnvironmentRejectsStartup() {
        var env = safeProd();
        env.setProperty("AICOSTOPS_DEV_MAILBOX_PATH", "C:\\mailbox\\out");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_DEV_MAILBOX_PATH");
    }

    @Test
    void fileBackedInvitationMailboxEnvironmentRejectsStartup() {
        var env = safeProd();
        env.setProperty("AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH", "C:\\mailbox\\invitations");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH");
    }

    @Test
    void localhostOnlyObjectStorageDefaultsRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "http://localhost:9000");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT");
    }

    @Test
    void blankObjectStorageCredentialsRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "https://s3.example.internal");
        env.setProperty("aicostops.storage.secret-key", "  ");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_SECRET_KEY");
    }

    @Test
    void weakDefaultDatabasePasswordRejectsStartup() {
        var env = safeProd();
        env.setProperty("spring.datasource.password", "change-me-local-only");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void localhostAllowedOriginsRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allowed-origins", "http://localhost:8080");

        assertThatThrownBy(() -> new ProductionConfigurationValidator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_ALLOWED_ORIGINS");
    }

    @Test
    void explicitSafeProductionValuesPass() {
        assertThatCode(() -> new ProductionConfigurationValidator(safeProd()).validate())
                .doesNotThrowAnyException();
    }

    private static MockEnvironment prod() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static MockEnvironment safeProd() {
        var env = prod();
        env.setProperty("aicostops.auth.jwt-signing-secret", STRONG_KEY);
        env.setProperty("aicostops.auth.dev-bootstrap-enabled", "false");
        env.setProperty("aicostops.auth.allow-public-registration", "false");
        env.setProperty("aicostops.auth.refresh-cookie-secure", "true");
        env.setProperty("aicostops.auth.allowed-origins", "https://costops.example.com");
        env.setProperty("aicostops.storage.endpoint", "https://s3.example.internal");
        env.setProperty("aicostops.storage.access-key", "prod-storage-access");
        env.setProperty("aicostops.storage.secret-key", "prod-storage-secret");
        env.setProperty("aicostops.storage.bucket", "aicostops-evidence-prod");
        env.setProperty("spring.datasource.password", "prod-db-secret");
        env.setProperty("aicostops.gateway.dev-bootstrap-enabled", "false");
        env.setProperty("aicostops.gateway.credential-hmac-key-v1", M11_KEY);
        env.setProperty("aicostops.gateway.request-hmac-key-v1", M11_KEY);
        env.setProperty("aicostops.gateway.provider-kek-v1", M11_KEY);
        return env;
    }
}