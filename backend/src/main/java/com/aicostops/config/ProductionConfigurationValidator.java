package com.aicostops.config;

import com.aicostops.gatewayadmin.security.GatewayKeyCodec;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-fast production guard. Active only under the {@code prod} profile; it
 * validates the resolved runtime configuration once during startup and rejects
 * dev-only, weak, or unsafe values before the application becomes ready.
 *
 * <p>Host locality is decided by resolving the URI host and classifying every
 * resolved address, so alternate loopback spellings ({@code 0.0.0.0},
 * {@code 127.1}, {@code 2130706433}, IPv4-mapped IPv6) cannot slip through a
 * substring match. Plaintext object storage is allowed only over an explicitly
 * opted-in private network; a public plaintext endpoint is always rejected.
 *
 * <p>Error messages name the environment variable an operator must fix but
 * never print the configured value, so secrets and resolved addresses are not
 * echoed to logs. The TLS boundary is intentionally outside this process: a
 * reverse proxy or ingress terminates HTTPS and reaches the backend over a
 * private HTTP hop.
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements InitializingBean {

    private static final String LOCAL_DEV_PASSWORD = "change-me-local-only";
    private static final int MINIMUM_JWT_KEY_LENGTH = 32;
    /**
     * Public example material shipped in {@code .env.example} and
     * {@code application-local.yml}. A production deployment that inherits
     * either value has a credential that anyone with repository read access
     * knows, regardless of its length.
     */
    private static final String LOCAL_DEV_JWT_PREFIX = "local-development-only-signing-key";
    private static final List<String> LOCAL_DEV_STORAGE_CREDENTIALS = List.of(
            "aicostops", LOCAL_DEV_PASSWORD);

    private static final String STORAGE_ENDPOINT_VARIABLE = "AICOSTOPS_STORAGE_ENDPOINT";
    private static final String STORAGE_PLAINTEXT_INTERNAL_PROPERTY =
            "aicostops.storage.allow-plaintext-internal";
    private static final String ALLOWED_ORIGINS_VARIABLE = "AICOSTOPS_ALLOWED_ORIGINS";

    private final Environment environment;
    private final HostResolver hostResolver;

    public ProductionConfigurationValidator(Environment environment) {
        this(environment, InetAddress::getAllByName);
    }

    /** Test seam: host resolution strategy (production uses the JVM resolver). */
    ProductionConfigurationValidator(Environment environment, HostResolver hostResolver) {
        this.environment = environment;
        this.hostResolver = hostResolver;
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Resolved locality of a URI host; {@code UNDETERMINED} fails closed. */
    private enum HostScope { LOCAL, INTERNAL, PUBLIC, UNDETERMINED }

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
        rejectResolvedTrue("AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED",
                "aicostops.gateway.dev-bootstrap-enabled",
                "the M11 Gateway dev bootstrap must stay disabled in production");
        require32ByteKey("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1",
                "aicostops.gateway.credential-hmac-key-v1");
        require32ByteKey("AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1",
                "aicostops.gateway.request-hmac-key-v1");
        require32ByteKey("AICOSTOPS_PROVIDER_KEK_V1", "aicostops.gateway.provider-kek-v1");
        rejectResolvedTrue("AICOSTOPS_ALLOW_PUBLIC_REGISTRATION", "aicostops.auth.allow-public-registration",
                "public registration requires an explicit reviewer-approved allow policy");
        rejectResolvedFalse("AICOSTOPS_REFRESH_COOKIE_SECURE", "aicostops.auth.refresh-cookie-secure",
                "the refresh cookie must be Secure in production");
        rejectExplicitEnvironment("AICOSTOPS_DEV_MAILBOX_PATH",
                "the file-backed dev mailbox is not supported in production");
        rejectExplicitEnvironment("AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH",
                "the file-backed dev invitation mailbox is not supported in production");
        rejectUnsafeStorageEndpoint();
        requireNonDefaultStorageCredential("AICOSTOPS_STORAGE_ACCESS_KEY",
                "aicostops.storage.access-key");
        requireNonDefaultStorageCredential("AICOSTOPS_STORAGE_SECRET_KEY",
                "aicostops.storage.secret-key");
        rejectResolvedValue("SPRING_DATASOURCE_PASSWORD", "spring.datasource.password",
                LOCAL_DEV_PASSWORD, "the local default database password is not acceptable in production");
        rejectUnsafeAllowedOrigins();
    }

    private void requireStrongJwtSecret() {
        var secret = normalized("aicostops.auth.jwt-signing-secret");
        if (secret.length() < MINIMUM_JWT_KEY_LENGTH) {
            throw fail("AICOSTOPS_JWT_SIGNING_KEY",
                    "the JWT signing key must be set to a strong value of at least "
                            + MINIMUM_JWT_KEY_LENGTH + " characters");
        }
        if (secret.toLowerCase(Locale.ROOT).startsWith(LOCAL_DEV_JWT_PREFIX)) {
            throw fail("AICOSTOPS_JWT_SIGNING_KEY",
                    "the repository's documented local-development signing key is not acceptable "
                            + "in production");
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

    /**
     * Object storage must be an absolute HTTP(S) URI whose host resolves to a
     * non-local address. Plaintext HTTP is accepted only on a private network
     * behind the explicit {@code AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL}
     * opt-in; a public plaintext endpoint never passes. An unresolvable host
     * cannot be classified and therefore fails closed.
     */
    private void rejectUnsafeStorageEndpoint() {
        var endpoint = normalized("aicostops.storage.endpoint");
        if (endpoint.isBlank()) {
            throw fail(STORAGE_ENDPOINT_VARIABLE,
                    "the object storage endpoint must be set to a non-local HTTP(S) address");
        }
        var uri = parseHttpUri(endpoint);
        if (uri == null) {
            throw fail(STORAGE_ENDPOINT_VARIABLE,
                    "the object storage endpoint must be an absolute http(s) URI with a host");
        }
        var scope = classifyHost(uri.getHost());
        if (scope == HostScope.UNDETERMINED) {
            throw fail(STORAGE_ENDPOINT_VARIABLE,
                    "the object storage endpoint host must resolve so its locality can be verified");
        }
        if (scope == HostScope.LOCAL) {
            throw fail(STORAGE_ENDPOINT_VARIABLE,
                    "loopback object storage defaults are not acceptable in production");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if (scope == HostScope.PUBLIC) {
            throw fail(STORAGE_ENDPOINT_VARIABLE,
                    "a public object storage endpoint must use https");
        }
        if (!Boolean.parseBoolean(normalized(STORAGE_PLAINTEXT_INTERNAL_PROPERTY))) {
            throw fail(STORAGE_ENDPOINT_VARIABLE,
                    "a plaintext object storage endpoint on a private network requires the explicit "
                            + "AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL=true opt-in");
        }
    }

    /**
     * Every comma-separated origin must be an absolute HTTP(S) URI with a host
     * that does not resolve to a local address. Hosts containing the text
     * "localhost" are not special; only proven loopback/any-local targets are
     * rejected, so a public name such as {@code localhost.example.com} passes.
     */
    private void rejectUnsafeAllowedOrigins() {
        var origins = normalized("aicostops.auth.allowed-origins");
        if (origins.isBlank()) {
            throw fail(ALLOWED_ORIGINS_VARIABLE,
                    "explicit non-loopback allowed origins are required in production");
        }
        for (var candidate : origins.split(",", -1)) {
            var origin = candidate.trim();
            if (origin.isEmpty()) {
                throw fail(ALLOWED_ORIGINS_VARIABLE,
                        "the allowed origins list must not contain empty entries");
            }
            var uri = parseHttpUri(origin);
            if (uri == null) {
                throw fail(ALLOWED_ORIGINS_VARIABLE,
                        "every allowed origin must be an absolute http(s) URI with a host");
            }
            if (classifyHost(uri.getHost()) == HostScope.LOCAL) {
                throw fail(ALLOWED_ORIGINS_VARIABLE,
                        "loopback allowed origins are not acceptable in production");
            }
        }
    }

    /**
     * Parses an absolute hierarchical HTTP(S) URI with a non-blank host.
     * Non-standard numeric forms such as {@code http://127.1:9000} fail here
     * because {@link URI#getHost()} returns {@code null}; rejecting them is
     * the safe outcome since no production endpoint needs that spelling.
     */
    private static URI parseHttpUri(String value) {
        try {
            var uri = new URI(value);
            var scheme = uri.getScheme();
            var host = uri.getHost();
            if (scheme == null || uri.isOpaque() || host == null || host.isBlank()) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /**
     * Resolves the host and classifies every address. Any local answer wins so
     * a mixed public/loopback answer set cannot be used to reach loopback;
     * otherwise any non-public address marks the host as internal.
     */
    private HostScope classifyHost(String host) {
        if (host == null) {
            return HostScope.UNDETERMINED;
        }
        var candidate = host.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isEmpty()) {
            return HostScope.UNDETERMINED;
        }
        final InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(candidate);
        } catch (UnknownHostException ex) {
            return HostScope.UNDETERMINED;
        }
        if (addresses == null || addresses.length == 0) {
            return HostScope.UNDETERMINED;
        }
        var internal = false;
        for (var address : addresses) {
            if (address == null) {
                return HostScope.UNDETERMINED;
            }
            if (isLocalAddress(address)) {
                return HostScope.LOCAL;
            }
            if (!isPublicUnicast(address)) {
                internal = true;
            }
        }
        return internal ? HostScope.INTERNAL : HostScope.PUBLIC;
    }

    /**
     * Object storage credentials fail both on a blank value and on any of the
     * local defaults published in {@code .env.example}/{@code application.yml}.
     * The error names only the environment variable, never the resolved value.
     */
    private void requireNonDefaultStorageCredential(String envName, String property) {
        var value = normalized(property);
        if (value.isBlank()) {
            throw fail(envName, "must be set explicitly in production");
        }
        if (LOCAL_DEV_STORAGE_CREDENTIALS.stream().anyMatch(value::equalsIgnoreCase)) {
            throw fail(envName,
                    "the repository's documented local-development storage credential is not "
                            + "acceptable in production");
        }
    }

    private void require32ByteKey(String envName, String property) {
        try {
            GatewayKeyCodec.decode32ByteKey(envName, normalized(property));
        } catch (IllegalStateException ex) {
            throw fail(envName, ex.getMessage());
        }
    }

    private static boolean isLocalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress()) {
            return true;
        }
        // Some JVMs keep an IPv4-mapped address as Inet6Address; unwrap it so
        // [::ffff:127.0.0.1] is still recognised as loopback, not internal.
        var raw = address.getAddress();
        if (raw != null && raw.length == 16 && isIpv4Mapped(raw)) {
            int b0 = raw[12] & 0xFF;
            int b1 = raw[13] & 0xFF;
            return b0 == 0 || b0 == 127 || (b0 == 169 && b1 == 254);
        }
        return false;
    }

    /**
     * Mirrors the Gateway's public-unicast policy: RFC1918, IPv6 ULA, CGNAT,
     * documentation, benchmarking, multicast and reserved ranges are not
     * globally routable, so they count as internal.
     */
    private static boolean isPublicUnicast(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isMulticastAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) {
            return false;
        }
        var raw = address.getAddress();
        if (raw == null) {
            return false;
        }
        if (raw.length == 4) {
            return isGloballyRoutableIpv4(raw);
        }
        return isGloballyRoutableIpv6(raw);
    }

    private static boolean isGloballyRoutableIpv4(byte[] raw) {
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        int b2 = raw[2] & 0xFF;
        int b3 = raw[3] & 0xFF;
        if (b0 == 0) {
            return false;
        }
        if (b0 == 10) {
            return false;
        }
        if (b0 == 100 && (b1 & 0xC0) == 0x40) {
            return false;
        }
        if (b0 == 127) {
            return false;
        }
        if (b0 == 169 && b1 == 254) {
            return false;
        }
        if (b0 == 172 && (b1 & 0xF0) == 0x10) {
            return false;
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return false;
        }
        if (b0 == 192 && b1 == 0 && b2 == 2) {
            return false;
        }
        if (b0 == 192 && b1 == 168) {
            return false;
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return false;
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {
            return false;
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {
            return false;
        }
        if ((b0 & 0xF0) == 0xE0) {
            return false;
        }
        if ((b0 & 0xF0) == 0xF0) {
            return false;
        }
        return !(b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255);
    }

    private static boolean isGloballyRoutableIpv6(byte[] raw) {
        if (raw.length != 16) {
            return false;
        }
        if (isIpv4Mapped(raw)) {
            return isGloballyRoutableIpv4(new byte[] {raw[12], raw[13], raw[14], raw[15]});
        }
        int b0 = raw[0] & 0xFF;
        int b1 = raw[1] & 0xFF;
        if ((b0 & 0xFE) == 0xFC) {
            return false;
        }
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return false;
        }
        if (b0 == 0xFF) {
            return false;
        }
        if (isDocumentationV6(raw)) {
            return false;
        }
        return (b0 & 0xE0) == 0x20;
    }

    private static boolean isIpv4Mapped(byte[] raw) {
        for (var i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
    }

    private static boolean isDocumentationV6(byte[] raw) {
        return (raw[0] & 0xFF) == 0x20 && (raw[1] & 0xFF) == 0x01
                && (raw[2] & 0xFF) == 0x0D && (raw[3] & 0xFF) == 0xB8;
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
