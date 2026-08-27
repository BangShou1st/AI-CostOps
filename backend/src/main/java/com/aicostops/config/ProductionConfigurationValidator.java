package com.aicostops.config;

import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast production guard. Active only under the {@code prod} profile; it
 * validates the resolved runtime configuration once during startup and rejects
 * dev-only, weak, or unsafe values before the application becomes ready.
 *
 * <p>Error messages name the environment variable an operator must fix but
 * never print the configured value, so secrets are not echoed to logs. The
 * TLS boundary is intentionally outside this process: a reverse proxy or
 * ingress terminates HTTPS and reaches the backend over a private HTTP hop.
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements InitializingBean {

    private static final String LOCAL_DEV_PASSWORD = "change-me-local-only";
    private static final int MINIMUM_JWT_KEY_LENGTH = 32;

    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            return;
        }
        requireStrongJwtSecret();
        rejectResolvedTrue("AICOSTOPS_DEV_BOOTSTRAP_ENABLED", "aicostops.auth.dev-bootstrap-enabled",
                "the dev bootstrap must stay disabled in production");
        rejectResolvedTrue("AICOSTOPS_ALLOW_PUBLIC_REGISTRATION", "aicostops.auth.allow-public-registration",
                "public registration requires an explicit reviewer-approved allow policy");
        rejectResolvedFalse("AICOSTOPS_REFRESH_COOKIE_SECURE", "aicostops.auth.refresh-cookie-secure",
                "the refresh cookie must be Secure in production");
        rejectExplicitEnvironment("AICOSTOPS_DEV_MAILBOX_PATH",
                "the file-backed dev mailbox is not supported in production");
        rejectExplicitEnvironment("AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH",
                "the file-backed dev invitation mailbox is not supported in production");
        rejectLocalhostStorageEndpoint();
        requireNonBlank("AICOSTOPS_STORAGE_ACCESS_KEY", "aicostops.storage.access-key");
        requireNonBlank("AICOSTOPS_STORAGE_SECRET_KEY", "aicostops.storage.secret-key");
        rejectResolvedValue("SPRING_DATASOURCE_PASSWORD", "spring.datasource.password",
                LOCAL_DEV_PASSWORD, "the local default database password is not acceptable in production");
        rejectLocalhostAllowedOrigins();
    }

    private void requireStrongJwtSecret() {
        var secret = normalized("aicostops.auth.jwt-signing-secret");
        if (secret.length() < MINIMUM_JWT_KEY_LENGTH) {
            throw fail("AICOSTOPS_JWT_SIGNING_KEY",
                    "the JWT signing key must be set to a strong value of at least "
                            + MINIMUM_JWT_KEY_LENGTH + " characters");
        }
    }

    private void rejectResolvedTrue(String envName, String property, String reason) {
        if (Boolean.parseBoolean(normalized(property))) {
            throw fail(envName, reason);
        }
    }

    private void rejectResolvedFalse(String envName, String property, String reason) {
        if (!Boolean.parseBoolean(normalized(property))) {
            throw fail(envName, reason);
        }
    }

    private void rejectResolvedValue(String envName, String property, String forbidden, String reason) {
        if (forbidden.equalsIgnoreCase(normalized(property))) {
            throw fail(envName, reason);
        }
    }

    private void rejectExplicitEnvironment(String envName, String reason) {
        if (environment.containsProperty(envName)) {
            throw fail(envName, reason);
        }
    }

    private void rejectLocalhostStorageEndpoint() {
        var endpoint = normalized("aicostops.storage.endpoint");
        if (endpoint.isBlank()) {
            throw fail("AICOSTOPS_STORAGE_ENDPOINT",
                    "the object storage endpoint must be set to a non-local HTTP(S) address");
        }
        if (isLocalhost(endpoint)) {
            throw fail("AICOSTOPS_STORAGE_ENDPOINT",
                    "loopback object storage defaults are not acceptable in production");
        }
    }

    private void rejectLocalhostAllowedOrigins() {
        var origins = normalized("aicostops.auth.allowed-origins");
        if (origins.isBlank() || isLocalhost(origins)) {
            throw fail("AICOSTOPS_ALLOWED_ORIGINS",
                    "explicit non-loopback allowed origins are required in production");
        }
    }

    private void requireNonBlank(String envName, String property) {
        if (normalized(property).isBlank()) {
            throw fail(envName, "must be set explicitly in production");
        }
    }

    private static boolean isLocalhost(String value) {
        var lower = value.toLowerCase();
        return lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("[::1]");
    }

    private String normalized(String property) {
        var value = environment.getProperty(property);
        return value == null ? "" : value.trim();
    }

    private static IllegalStateException fail(String envName, String detail) {
        return new IllegalStateException(
                "Unsafe production configuration: " + envName + " — " + detail);
    }
}