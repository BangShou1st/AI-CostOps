package com.aicostops.providerhub.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.gatewayadmin.security.ProviderCredentialEncryptor;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.providerhub.infrastructure.ModelDiscoveryMapper;
import com.aicostops.providerhub.infrastructure.ProbeCredentialMapper;
import com.aicostops.providerhub.infrastructure.ProviderConnectionMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Explicit bounded capability probe (M18 V3).
 *
 * <p>Uses one fixed synthetic prompt; never persists prompt, completion or
 * raw provider bytes. Only backend-observed evidence may mark a capability
 * VERIFIED &mdash; callers can never set VERIFIED directly. Never runs inside
 * a DB transaction: HTTP first, then a single audit + observation update.
 */
@Service
public class ModelProbeService {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BODY_BYTES = 65536;
    private static final int MAX_STREAM_BYTES = 65536;
    private static final int MAX_STREAM_EVENTS = 50;
    private static final String SYNTHETIC_BODY =
            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\"," +
            "\"content\":\"Reply with exactly: {\\\"ok\\\":true}\"}]," +
            "\"max_tokens\":16,\"response_format\":{\"type\":\"json_object\"},\"stream\":false}";

    private final AuthorizationContextService authorizationContexts;
    private final ProviderConnectionMapper connections;
    private final ModelDiscoveryMapper discovery;
    private final ProbeCredentialMapper credentials;
    private final CustomEndpointValidator endpointValidator;
    private final ProviderControlPlaneTransport transport;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String providerKek;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ModelProbeService(
            AuthorizationContextService authorizationContexts,
            ProviderConnectionMapper connections,
            ModelDiscoveryMapper discovery,
            ProbeCredentialMapper credentials,
            CustomEndpointValidator endpointValidator,
            ProviderControlPlaneTransport transport,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${aicostops.gateway.provider-kek-v1:}") String providerKek) {
        this.authorizationContexts = authorizationContexts;
        this.connections = connections;
        this.discovery = discovery;
        this.credentials = credentials;
        this.endpointValidator = endpointValidator;
        this.transport = transport;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.providerKek = providerKek;
    }

    public ProbeResult probe(AuthenticatedUser user, long profileId, long discoveryId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        var row = discovery.find(discoveryId, context.organizationId());
        if (row == null || row.providerConnectionProfileId() != profileId) {
            throw notFound("Model discovery was not found.");
        }
        var outcome = execute(profile, row.providerModelName());
        var verifiedJson = capabilitiesJson(outcome.capabilities());
        var now = clock.instant();
        discovery.recordProbe(discoveryId, context.organizationId(), verifiedJson,
                outcome.pass() ? "PASS" : "FAIL", outcome.errorCode(), now);
        audit.append("PROVIDER_MODEL_PROBED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", profileId, Map.of("model", row.providerModelName(),
                        "result", outcome.pass() ? "PASS" : "FAIL",
                        "errorCode", outcome.errorCode() == null ? "NONE" : outcome.errorCode()));
        return outcome;
    }

    private ProbeResult execute(com.aicostops.providerhub.domain.ProviderConnection profile, String modelName) {
        var capabilities = new LinkedHashMap<String, String>();
        capabilities.put("CHAT_COMPLETIONS", "UNKNOWN");
        capabilities.put("SSE_STREAMING", "UNKNOWN");
        capabilities.put("USAGE", "UNKNOWN");
        capabilities.put("STRUCTURED_JSON", "UNKNOWN");
        try {
            endpointValidator.validateEndpoint(profile.baseUrl());
        } catch (DomainException ex) {
            return new ProbeResult(false, capabilities, "ENDPOINT_BLOCKED");
        }
        final String secret;
        final String secretKind;
        if ("NONE".equals(profile.authType())) {
            secret = null;
            secretKind = null;
        } else {
            var credential = credentials.findActive(profile.organizationId(), profile.providerAccountId());
            if (credential == null) {
                return new ProbeResult(false, capabilities, "AUTHENTICATION_FAILED");
            }
            if (providerKek == null || providerKek.isBlank()) {
                return new ProbeResult(false, capabilities, "PROVIDER_UNAVAILABLE");
            }
            try {
                secret = new ProviderCredentialEncryptor(providerKek).decrypt(
                        credential.ciphertext(), credential.nonce(),
                        profile.organizationId(), profile.providerAccountId(),
                        credential.credentialType(), credential.encryptionKeyVersion());
            } catch (Exception ex) {
                return new ProbeResult(false, capabilities, "AUTHENTICATION_FAILED");
            }
            secretKind = credential.credentialType();
        }
        try {
            var body = probeOnce(profile, modelName, secret, secretKind);
            if (body == null) {
                capabilities.put("CHAT_COMPLETIONS", "UNSUPPORTED");
                capabilities.put("SSE_STREAMING", "UNKNOWN");
                return new ProbeResult(false, capabilities, "MODEL_NOT_FOUND");
            }
            capabilities.put("CHAT_COMPLETIONS", body.contains("\"choices\"") ? "VERIFIED" : "UNSUPPORTED");
            capabilities.put("USAGE", body.contains("\"usage\"") ? "VERIFIED" : "UNSUPPORTED");
            capabilities.put("STRUCTURED_JSON", looksStructured(body) ? "VERIFIED" : "UNSUPPORTED");
            // P1: SSE_STREAMING must be proven by a real bounded streaming call, never inferred
            // from the non-streaming completion above.
            if ("VERIFIED".equals(capabilities.get("CHAT_COMPLETIONS"))) {
                capabilities.put("SSE_STREAMING", probeStreaming(profile, modelName, secret, secretKind));
            } else {
                capabilities.put("SSE_STREAMING", "UNKNOWN");
            }
            var pass = "VERIFIED".equals(capabilities.get("CHAT_COMPLETIONS"));
            return new ProbeResult(pass, capabilities, pass ? null : "PROTOCOL_UNSUPPORTED");
        } catch (DomainException ex) {
            return new ProbeResult(false, capabilities, "ENDPOINT_BLOCKED");
        } catch (SSLHandshakeException ex) {
            return new ProbeResult(false, capabilities, "TLS_FAILED");
        } catch (java.net.ConnectException | java.net.UnknownHostException ex) {
            return new ProbeResult(false, capabilities, "DNS_FAILED");
        } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException ex) {
            return new ProbeResult(false, capabilities, "CONNECTION_TIMEOUT");
        } catch (ProbeHttpStatus ex) {
            return new ProbeResult(false, capabilities, ex.errorCode);
        } catch (Exception ex) {
            return new ProbeResult(false, capabilities, "PROVIDER_UNAVAILABLE");
        }
    }

    /**
     * Bounded completion probe over the shared control-plane transport (single DNS resolution
     * bound to the actual connect per hop, proxy disabled, bounded body, manual redirects with
     * per-hop SSRF re-validation and same-origin secret policy).
     */
    private String probeOnce(com.aicostops.providerhub.domain.ProviderConnection profile,
            String modelName, String secret, String secretKind) throws Exception {
        var target = join(profile.baseUrl(), profile.completionPath());
        endpointValidator.validateEndpoint(target);
        var origin = URI.create(target);
        var userAgent = ProviderTransportSupport.serverUserAgent(profile.userAgent(),
                profile.templateCode());
        var payload = SYNTHETIC_BODY.formatted(escapeJson(modelName));
        var authenticated = secret != null && !"NONE".equals(profile.authType());
        var current = target;
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final ProviderControlPlaneTransport.Result response;
            try {
                response = transport.post(current,
                        probeHeaders(profile, userAgent, secret, secretKind, "application/json"),
                        payload, profile.connectTimeoutMs(), profile.responseTimeoutMs());
            } catch (ProviderControlPlaneTransport.TransportException ex) {
                throw mapProbeTransport(ex);
            }
            var status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                var location = response.firstHeader("location");
                if (location.isBlank() || hop == MAX_REDIRECTS) {
                    throw new ProbeHttpStatus("ENDPOINT_BLOCKED");
                }
                final URI resolved;
                try {
                    resolved = resolveRedirect(origin, current, location);
                } catch (IllegalArgumentException ex) {
                    throw new ProbeHttpStatus("ENDPOINT_BLOCKED");
                }
                endpointValidator.validateRedirectTarget(resolved.toString());
                // P1: never forward a Provider secret to a different origin. Authenticated
                // cross-origin redirect is rejected without emitting the next request.
                if (authenticated && !sameOrigin(origin, resolved)) {
                    throw new ProbeHttpStatus("ENDPOINT_BLOCKED");
                }
                current = resolved.toString();
                continue;
            }
            if (status == 401 || status == 403) {
                throw new ProbeHttpStatus("AUTHENTICATION_FAILED");
            }
            if (status == 404) {
                return null;
            }
            if (status == 429) {
                throw new ProbeHttpStatus("RATE_LIMITED");
            }
            if (status < 200 || status >= 300) {
                throw new ProbeHttpStatus("PROVIDER_UNAVAILABLE");
            }
            return response.bodyAsUtf8();
        }
        throw new ProbeHttpStatus("PROVIDER_UNAVAILABLE");
    }

    /**
     * P1: bounded streaming capability probe. Fixed synthetic prompt with {@code stream=true};
     * requires {@code text/event-stream}, at least one legal Chat Completion chunk and terminal
     * handling; bounded bytes/events/time; never persists prompt/completion/raw bytes.
     * Returns VERIFIED / UNSUPPORTED / UNKNOWN independently of the non-streaming result.
     */
    String probeStreaming(com.aicostops.providerhub.domain.ProviderConnection profile,
            String modelName, String secret, String secretKind) {
        var streamPayload = ("{\"model\":\"%s\",\"messages\":[{\"role\":\"user\"," +
                "\"content\":\"Reply with exactly: {\\\"ok\\\":true}\"}]," +
                "\"max_tokens\":16,\"stream\":true}").formatted(escapeJson(modelName));
        try {
            var target = join(profile.baseUrl(), profile.completionPath());
            endpointValidator.validateEndpoint(target);
            var origin = URI.create(target);
            var authenticated = secret != null && !"NONE".equals(profile.authType());
            var userAgent = ProviderTransportSupport.serverUserAgent(profile.userAgent(),
                    profile.templateCode());
            var current = target;
            for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
                final ProviderControlPlaneTransport.Result response;
                try {
                    response = transport.post(current,
                            probeHeaders(profile, userAgent, secret, secretKind, "text/event-stream"),
                            streamPayload, profile.connectTimeoutMs(), profile.responseTimeoutMs());
                } catch (ProviderControlPlaneTransport.TransportException ex) {
                    return "UNKNOWN";
                }
                var status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    var location = response.firstHeader("location");
                    if (location.isBlank() || hop == MAX_REDIRECTS) {
                        return "UNSUPPORTED";
                    }
                    final URI resolved;
                    try {
                        resolved = resolveRedirect(origin, current, location);
                    } catch (IllegalArgumentException ex) {
                        return "UNSUPPORTED";
                    }
                    try {
                        endpointValidator.validateRedirectTarget(resolved.toString());
                    } catch (DomainException ex) {
                        return "UNSUPPORTED";
                    }
                    if (authenticated && !sameOrigin(origin, resolved)) {
                        return "UNSUPPORTED";
                    }
                    current = resolved.toString();
                    continue;
                }
                if (status == 401 || status == 403 || status == 404) {
                    return "UNKNOWN";
                }
                if (status < 200 || status >= 300) {
                    return "UNSUPPORTED";
                }
                var contentType = response.firstHeader("content-type");
                if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                    return "UNSUPPORTED";
                }
                try (var stream = new java.io.ByteArrayInputStream(response.body())) {
                    return readStreamVerdict(stream);
                }
            }
            return "UNKNOWN";
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private String readStreamVerdict(java.io.InputStream stream) throws Exception {
        var buf = new byte[4096];
        var total = new StringBuilder();
        var read = 0;
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int n;
        while ((n = stream.read(buf)) != -1) {
            if (System.nanoTime() > deadline) {
                break;
            }
            read += n;
            if (read > MAX_STREAM_BYTES) {
                break;
            }
            total.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (total.length() > MAX_STREAM_BYTES) {
                break;
            }
            if (countEvents(total.toString()) >= MAX_STREAM_EVENTS) {
                break;
            }
            // Early exit once a valid chunk + terminal marker are both observed.
            if (total.toString().contains("[DONE]") && containsChunk(total.toString())) {
                break;
            }
        }
        var text = total.toString();
        if (!containsChunk(text)) {
            return "UNSUPPORTED";
        }
        return "VERIFIED";
    }

    private static int countEvents(String text) {
        var count = 0;
        var idx = 0;
        while ((idx = text.indexOf("data:", idx)) != -1) {
            count++;
            idx += 5;
        }
        return count;
    }

    private boolean containsChunk(String text) {
        try {
            for (var line : text.split("\n")) {
                var trimmed = line.strip();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                var payload = trimmed.substring(5).strip();
                if (payload.isEmpty() || payload.equals("[DONE]")) {
                    continue;
                }
                var node = objectMapper.readTree(payload);
                if (node.has("choices")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private java.util.Map<String, String> probeHeaders(
            com.aicostops.providerhub.domain.ProviderConnection profile, String userAgent,
            String secret, String secretKind, String accept) {
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Accept", accept);
        headers.put("User-Agent", userAgent);
        if ("NONE".equals(profile.authType()) || secret == null) {
            return headers;
        }
        if ("BEARER".equals(profile.authType()) || "BEARER_TOKEN".equals(secretKind)) {
            headers.put("Authorization", "Bearer " + secret);
        } else {
            headers.put(profile.authHeaderName(), secret);
        }
        return headers;
    }

    private ProbeHttpStatus mapProbeTransport(ProviderControlPlaneTransport.TransportException ex) {
        return switch (ex.errorCode()) {
            case ProviderControlPlaneTransport.TransportException.DNS_FAILED ->
                new ProbeHttpStatus("DNS_FAILED");
            case ProviderControlPlaneTransport.TransportException.TLS_FAILED ->
                new ProbeHttpStatus("TLS_FAILED");
            case ProviderControlPlaneTransport.TransportException.CONNECTION_TIMEOUT ->
                new ProbeHttpStatus("CONNECTION_TIMEOUT");
            default -> new ProbeHttpStatus("PROVIDER_UNAVAILABLE");
        };
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static URI resolveRedirect(URI origin, String current, String location) {
        var base = URI.create(current);
        var resolved = base.resolve(location);
        if (resolved.getScheme() == null || resolved.getHost() == null) {
            throw new IllegalArgumentException("Redirect target is malformed");
        }
        return resolved;
    }

    private boolean looksStructured(String body) {
        try {
            var node = objectMapper.readTree(body);
            var text = node.toString();
            return text.contains("\"ok\"");
        } catch (Exception ex) {
            return false;
        }
    }

    private String capabilitiesJson(Map<String, String> capabilities) {
        try {
            return objectMapper.writeValueAsString(Map.of("capabilities", capabilities.entrySet().stream()
                    .filter(e -> "VERIFIED".equals(e.getValue())).map(Map.Entry::getKey).toList()));
        } catch (Exception ex) {
            return "{\"capabilities\": []}";
        }
    }

    private static String join(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        var b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + (path.startsWith("/") ? path : "/" + path);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Model discovery not found", detail);
    }

    private static final class ProbeHttpStatus extends Exception {
        private final String errorCode;

        private ProbeHttpStatus(String errorCode) {
            this.errorCode = errorCode;
        }
    }

    public record ProbeResult(boolean pass, Map<String, String> capabilities, String errorCode) {
    }
}
