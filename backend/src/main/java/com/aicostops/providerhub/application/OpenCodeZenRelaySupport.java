package com.aicostops.providerhub.application;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.ZenModelProtocol;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Shared safety and endpoint governance for OpenCode Zen Relay transport (V3 Maintenance).
 *
 * <p>Enforces:
 * <ul>
 *   <li>Normalized 2.x inference endpoint mapping (/openai/v1/chat/completions).</li>
 *   <li>Strict upstream target whitelisting: https scheme, opencode.ai host, port 443 only.</li>
 *   <li>Relay SSRF containment: loopback only, active fingerprint probe required.</li>
 *   <li>Credential isolation: no internal credentials forwarded to relay.</li>
 * </ul>
 */
public final class OpenCodeZenRelaySupport {

    public static final int DEFAULT_RELAY_PORT = 18992;
    public static final String TRUSTED_UPSTREAM_HOST = "opencode.ai";
    private static final int PROBE_TIMEOUT_MS = 1000;

    private OpenCodeZenRelaySupport() {
    }

    /**
     * Resolves the canonical 2.x Chat Completions inference endpoint without mutating
     * versioned database connection profiles.
     */
    public static String resolveInferenceEndpoint(String baseUrl, String completionPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return OpenCodeZenProtocolPolicy.inferenceEndpoint(ZenModelProtocol.CHAT);
        }
        var normalizedBase = baseUrl.strip().replaceAll("/+$", "");
        var normalizedPath = completionPath == null || completionPath.isBlank()
                ? OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH : completionPath.strip();
        if (OpenCodeZenProtocolPolicy.INFERENCE_BASE_URL.equalsIgnoreCase(normalizedBase)
                && (OpenCodeZenProtocolPolicy.CHAT_COMPLETIONS_PATH.equalsIgnoreCase(normalizedPath)
                || "/openai/v1/chat/completions".equalsIgnoreCase(normalizedPath))) {
            return OpenCodeZenProtocolPolicy.inferenceEndpoint(ZenModelProtocol.CHAT);
        }
        return normalizedBase + (normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath);
    }

    /**
     * Strictly verifies that the upstream target specified for relay egress is locked to
     * official OpenCode Zen infrastructure:
     * 1. Must use https scheme.
     * 2. Host must be strictly opencode.ai (preventing internal host/port tunneling or SSRF).
     * 3. Port must be 443 or default HTTPS.
     * 4. UserInfo is strictly forbidden.
     */
    public static void validateUpstreamTarget(String upstreamTarget) {
        if (upstreamTarget == null || upstreamTarget.isBlank()) {
            throw new IllegalArgumentException("Relay target cannot be null or blank");
        }
        final URI uri;
        try {
            uri = URI.create(upstreamTarget.strip());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed relay target URI: " + upstreamTarget, ex);
        }
        var scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("Relay target must use https scheme: " + upstreamTarget);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Relay target must not embed credentials");
        }
        var host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(TRUSTED_UPSTREAM_HOST)) {
            throw new IllegalArgumentException("Relay target host must be strictly " + TRUSTED_UPSTREAM_HOST + ": " + host);
        }
        var port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalArgumentException("Relay target port must be 443 or default: " + port);
        }
    }

    /**
     * Validates that the relay URL points to a loopback address and that the listening process
     * actively proves its identity as the official Bun transparent relay.
     */
    public static boolean isTrustedRelay(String relayUrl) {
        if (relayUrl == null || relayUrl.isBlank()) {
            return false;
        }
        try {
            var uri = URI.create(relayUrl.strip());
            var host = uri.getHost();
            if (host == null) {
                return false;
            }
            var lowerHost = host.toLowerCase(Locale.ROOT);
            if (!lowerHost.equals("127.0.0.1") && !lowerHost.equals("localhost") && !lowerHost.equals("::1")) {
                return false;
            }
            if (uri.getRawUserInfo() != null) {
                return false;
            }
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).build();
            var response = client.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 400
                    && response.body() != null
                    && response.body().contains("x-relay-target header required");
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Validates whether a resolved socket address corresponds to a trusted loopback relay.
     */
    public static boolean isTrustedLoopbackAddress(InetAddress address, int port) {
        if (address == null) {
            return false;
        }
        return address.isLoopbackAddress() && port == DEFAULT_RELAY_PORT;
    }
}
