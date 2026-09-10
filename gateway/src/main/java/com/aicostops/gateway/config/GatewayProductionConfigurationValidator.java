package com.aicostops.gateway.config;

import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production fail-fast validator (AIC-091 section 12). Under the {@code prod}
 * profile startup rejects malformed/missing Base64 32-byte HMAC/KEK secrets,
 * an enabled dev bootstrap, invalid resource limits, or missing/default
 * datasource credentials. No production secret ever appears in committed
 * configuration; these values must come from the runtime environment.
 *
 * <p>Datasource credentials are read from the resolved Spring
 * {@link Environment} (the same {@code spring.datasource.*} values the
 * connection pool uses), mirroring the Backend production validator. Error
 * messages name the environment variable an operator must fix but never print
 * the configured value.
 */
@Component
@Profile("prod")
public class GatewayProductionConfigurationValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(
            GatewayProductionConfigurationValidator.class);

    private static final String LOCAL_DEV_PASSWORD = "change-me-local-only";

    private final GatewayProperties properties;
    private final Environment environment;

    public GatewayProductionConfigurationValidator(GatewayProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        requireSecret("credential-hmac-key-v1", properties.getCredentialHmacKeyV1());
        requireSecret("request-hmac-key-v1", properties.getRequestHmacKeyV1());
        requireSecret("provider-kek-v1", properties.getProviderKekV1());
        if (properties.isDevBootstrapEnabled()) {
            throw new IllegalStateException(
                    "aicostops.gateway.dev-bootstrap-enabled must be false in production");
        }
        if (properties.getDevRawKey() != null && !properties.getDevRawKey().isBlank()) {
            throw new IllegalStateException(
                    "aicostops.gateway.dev-raw-key must not be set in production");
        }
        requireDatasourceCredential();
        properties.validate();
        log.info("Gateway production configuration validated");
    }

    private void requireDatasourceCredential() {
        if (normalized("spring.datasource.username").isBlank()) {
            throw fail("SPRING_DATASOURCE_USERNAME", "must be set explicitly in production");
        }
        var password = normalized("spring.datasource.password");
        if (password.isBlank()) {
            throw fail("SPRING_DATASOURCE_PASSWORD", "must be set explicitly in production");
        }
        if (LOCAL_DEV_PASSWORD.equalsIgnoreCase(password)) {
            throw fail("SPRING_DATASOURCE_PASSWORD",
                    "the local default database password is not acceptable in production");
        }
    }

    private String normalized(String property) {
        var value = environment.getProperty(property);
        return value == null ? "" : value.trim();
    }

    private static IllegalStateException fail(String envName, String detail) {
        return new IllegalStateException(
                "Unsafe production configuration: " + envName + " — " + detail);
    }

    /** A production secret must be Base64 that decodes to exactly 32 random bytes. */
    public static void requireSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "aicostops.gateway." + name + " is required in production");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "aicostops.gateway." + name + " must be valid Base64");
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "aicostops.gateway." + name + " must decode to exactly 32 bytes, got "
                            + decoded.length);
        }
    }
}