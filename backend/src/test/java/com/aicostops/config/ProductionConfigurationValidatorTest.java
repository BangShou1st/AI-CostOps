package com.aicostops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

/**
 * Production fail-fast validation: the {@code prod} profile must reject
 * dev-only, weak, or unsafe runtime configuration before the application
 * becomes ready. Validation reads the resolved Spring properties (the same
 * names operators set through the environment), and every error message names
 * the environment variable without printing its value.
 *
 * <p>Host locality is decided by resolving the URI host, so the tests inject a
 * deterministic resolver instead of depending on the runner's DNS.
 */
class ProductionConfigurationValidatorTest {

    private static final String STRONG_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String M11_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Test
    void blankJwtSigningKeyRejectsStartup() {
        var env = prod();
        env.setProperty("aicostops.auth.jwt-signing-secret", "  ");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY");
    }

    @Test
    void shortJwtSigningKeyRejectsStartup() {
        var env = prod();
        env.setProperty("aicostops.auth.jwt-signing-secret", "short-key");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY");
    }

    @Test
    void documentedExampleJwtSigningKeyRejectsStartup() {
        // The value shipped in .env.example is 64 characters, so the length
        // check alone accepts it. A resolved deployment value equal to the
        // documented example must fail without echoing the key.
        var env = safeProd();
        env.setProperty("aicostops.auth.jwt-signing-secret",
                "local-development-only-signing-key-change-before-production-2026");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY")
                .hasMessageNotContaining("local-development-only-signing-key");
    }

    @Test
    void localDevelopmentJwtSigningKeyPrefixRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.jwt-signing-secret",
                "local-development-only-signing-key-rotated-but-still-public-2027");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY")
                .hasMessageNotContaining("rotated-but-still-public");
    }

    @Test
    void devBootstrapEnabledRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.dev-bootstrap-enabled", "true");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_DEV_BOOTSTRAP_ENABLED");
    }

    @Test
    void m11GatewayDevBootstrapEnabledRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.dev-bootstrap-enabled", "true");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED");
    }

    @Test
    void missingM11GatewayKeysRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.provider-kek-v1", "  ");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_PROVIDER_KEK_V1");
    }

    @Test
    void malformedBase64M11KeyRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.credential-hmac-key-v1", "not-base64!!");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1");
    }

    @Test
    void wrongLengthM11KeyRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.gateway.request-hmac-key-v1", "c2hvcnQ=");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1");
    }

    @Test
    void publicRegistrationEnabledRejectsStartupWithoutExplicitAllowPolicy() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allow-public-registration", "true");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_ALLOW_PUBLIC_REGISTRATION");
    }

    @Test
    void insecureRefreshCookieRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.refresh-cookie-secure", "false");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_REFRESH_COOKIE_SECURE");
    }

    @Test
    void fileBackedDevMailboxEnvironmentRejectsStartup() {
        var env = safeProd();
        env.setProperty("AICOSTOPS_DEV_MAILBOX_PATH", "C:\\mailbox\\out");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_DEV_MAILBOX_PATH");
    }

    @Test
    void fileBackedInvitationMailboxEnvironmentRejectsStartup() {
        var env = safeProd();
        env.setProperty("AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH", "C:\\mailbox\\invitations");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH");
    }

    @Test
    void localhostOnlyObjectStorageDefaultsRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "http://localhost:9000");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT");
    }

    @Test
    void wildcardAndNumericLoopbackStorageEndpointsRejectStartup() {
        // Each of these reaches the loopback interface with a plain HTTP
        // client, yet none contains the literal "localhost" or "127.0.0.1".
        // The internal-plaintext opt-in is on, so only locality can reject.
        for (var endpoint : List.of(
                "http://0.0.0.0:9000",
                "http://127.1:9000",
                "http://2130706433:9000",
                "http://[::ffff:127.0.0.1]:9000")) {
            var env = safeProd();
            env.setProperty("aicostops.storage.endpoint", endpoint);
            env.setProperty("aicostops.storage.allow-plaintext-internal", "true");

            assertThatThrownBy(() -> validator(env).validate())
                    .as("storage endpoint %s must be rejected", endpoint)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT")
                    .hasMessageNotContaining(endpoint);
        }
    }

    @Test
    void unmatchedStorageEndpointSchemeRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "minio:9000");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT");
    }

    @Test
    void unresolvableStorageEndpointRejectsStartupWithoutEchoingTheHost() {
        // Locality cannot be decided when the host does not resolve, so the
        // production guard must fail closed and name only the variable.
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "https://storage.invalid:9000");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT")
                .hasMessageNotContaining("storage.invalid");
    }

    @Test
    void mixedPublicAndLoopbackResolutionRejectsStartup() {
        // One loopback answer is enough: the client could land on it.
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "https://mixed.example.com:9000");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT")
                .hasMessageNotContaining("mixed.example.com");
    }

    @Test
    void publicPlaintextStorageEndpointRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "http://storage.example.com:9000");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ENDPOINT");
    }

    @Test
    void internalPlaintextStorageEndpointRejectsStartupWithoutExplicitOptIn() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "http://minio:9000");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL")
                .hasMessageNotContaining("minio")
                .hasMessageNotContaining("10.0.0.5");
    }

    @Test
    void internalPlaintextStorageEndpointPassesWithExplicitOptIn() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "http://minio:9000");
        env.setProperty("aicostops.storage.allow-plaintext-internal", "true");

        assertThatCode(() -> validator(env).validate()).doesNotThrowAnyException();
    }

    @Test
    void publicHttpsStorageEndpointPasses() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "https://storage.example.com:9000");

        assertThatCode(() -> validator(env).validate()).doesNotThrowAnyException();
    }

    @Test
    void blankObjectStorageCredentialsRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.storage.endpoint", "https://s3.example.internal");
        env.setProperty("aicostops.storage.secret-key", "  ");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_STORAGE_SECRET_KEY");
    }

    @Test
    void localDefaultObjectStorageCredentialsRejectStartup() {
        // The same two values are the documented defaults in application.yml
        // and .env.example; both credential properties must reject both of
        // them symmetrically, and no message may echo the resolved value.
        var properties = List.of(
                "aicostops.storage.access-key", "aicostops.storage.secret-key");
        var values = List.of("aicostops", "change-me-local-only");
        var expectedVariables = List.of(
                "AICOSTOPS_STORAGE_ACCESS_KEY", "AICOSTOPS_STORAGE_SECRET_KEY");
        for (int propertyIndex = 0; propertyIndex < properties.size(); propertyIndex++) {
            for (var value : values) {
                var env = safeProd();
                env.setProperty(properties.get(propertyIndex), value);

                assertThatThrownBy(() -> validator(env).validate())
                        .as("%s must reject the local default value",
                                properties.get(propertyIndex))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(expectedVariables.get(propertyIndex))
                        .hasMessageNotContaining(value);
            }
        }
    }

    @Test
    void weakDefaultDatabasePasswordRejectsStartup() {
        var env = safeProd();
        env.setProperty("spring.datasource.password", "change-me-local-only");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void localhostAllowedOriginsRejectStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allowed-origins", "http://localhost:8080");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_ALLOWED_ORIGINS");
    }

    @Test
    void wildcardAndNumericLoopbackAllowedOriginsRejectStartup() {
        for (var origin : List.of(
                "http://0.0.0.0:8080",
                "http://127.1:8080",
                "http://2130706433:8080",
                "http://[::ffff:127.0.0.1]:8080")) {
            var env = safeProd();
            env.setProperty("aicostops.auth.allowed-origins", origin);

            assertThatThrownBy(() -> validator(env).validate())
                    .as("allowed origin %s must be rejected", origin)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AICOSTOPS_ALLOWED_ORIGINS")
                    .hasMessageNotContaining(origin);
        }
    }

    @Test
    void localhostLikeHostnameIsNotTreatedAsLoopbackOrigin() {
        // A public DNS name that merely contains "localhost" is not a loopback
        // host and must not be rejected by substring matching.
        var env = safeProd();
        env.setProperty("aicostops.auth.allowed-origins", "https://localhost.example.com");

        assertThatCode(() -> validator(env).validate()).doesNotThrowAnyException();
    }

    @Test
    void multipleAllowedOriginsAreEachValidated() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allowed-origins",
                "https://costops.example.com, https://admin.example.com");

        assertThatCode(() -> validator(env).validate()).doesNotThrowAnyException();
    }

    @Test
    void emptyAllowedOriginEntryRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allowed-origins",
                "https://costops.example.com,,https://admin.example.com");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_ALLOWED_ORIGINS");
    }

    @Test
    void nonHttpAllowedOriginRejectsStartup() {
        var env = safeProd();
        env.setProperty("aicostops.auth.allowed-origins", "ftp://costops.example.com");

        assertThatThrownBy(() -> validator(env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AICOSTOPS_ALLOWED_ORIGINS");
    }

    @Test
    void explicitSafeProductionValuesPass() {
        assertThatCode(() -> validator(safeProd()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void springSelectsTheEnvironmentConstructorForProductionInjection() {
        var beanFactory = new DefaultListableBeanFactory();
        var processor = new AutowiredAnnotationBeanPostProcessor();
        processor.setBeanFactory(beanFactory);
        beanFactory.addBeanPostProcessor(processor);

        var candidates = processor.determineCandidateConstructors(
                ProductionConfigurationValidator.class, "productionConfigurationValidator");

        assertThat(candidates).hasSize(1);
        assertThat(candidates[0].getParameterTypes()).containsExactly(Environment.class);
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

    private static ProductionConfigurationValidator validator(MockEnvironment environment) {
        return new ProductionConfigurationValidator(environment,
                ProductionConfigurationValidatorTest::resolve);
    }

    /**
     * Deterministic stand-in for the JVM resolver so assertions never depend on
     * the runner's DNS. Literal addresses still go through the JDK parser so
     * numeric loopback forms are exercised for real.
     */
    private static InetAddress[] resolve(String host) throws UnknownHostException {
        var bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return switch (bare) {
            case "minio" -> new InetAddress[] {InetAddress.getByName("10.0.0.5")};
            case "s3.example.internal" -> new InetAddress[] {InetAddress.getByName("192.0.2.10")};
            case "storage.example.com" -> new InetAddress[] {InetAddress.getByName("8.8.8.8")};
            case "costops.example.com", "admin.example.com", "localhost.example.com" ->
                    new InetAddress[] {InetAddress.getByName("203.0.113.7")};
            case "mixed.example.com" -> new InetAddress[] {
                    InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
            case "storage.invalid" -> throw new UnknownHostException(bare);
            default -> InetAddress.getAllByName(bare);
        };
    }
}
