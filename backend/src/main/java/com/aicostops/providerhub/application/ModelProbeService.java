package com.aicostops.providerhub.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.gatewayadmin.security.ProviderCredentialEncryptor;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
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
import java.util.List;
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
    private final OpenCodeModelManifest openCodeManifest;
    private final OpenCodeZenTransportHeaders openCodeZenHeaders;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    /** Test and legacy constructor; production wiring uses the auto-configured shared request factory. */
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
            @Value("${aicostops.gateway.provider-kek-v1:}") String providerKek,
            OpenCodeModelManifest openCodeManifest) {
        this(authorizationContexts, connections, discovery, credentials, endpointValidator, transport,
                audit, objectMapper, clock, providerKek, openCodeManifest,
                new OpenCodeZenTransportHeaders(new OpenCodeZenRequestFactory(
                        ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT)));
    }

    @org.springframework.beans.factory.annotation.Autowired
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
            @Value("${aicostops.gateway.provider-kek-v1:}") String providerKek,
            OpenCodeModelManifest openCodeManifest,
            OpenCodeZenTransportHeaders openCodeZenHeaders) {
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
        this.openCodeManifest = openCodeManifest;
        this.openCodeZenHeaders = openCodeZenHeaders;
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
        // P1-4 OpenCode manifest gate + FREE_ONLY (2026-10-16): the built-in template is admitted
        // for free dispatch only, so Chat compatibility is necessary but never sufficient. PAID,
        // UNKNOWN, UNSUPPORTED and delisted ids must not reach Provider HTTP I/O at all. Zero
        // Provider I/O happens here; custom connections skip the manifest because their connection
        // itself is the explicit Chat contract.
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode())
                && !openCodeManifest.isFreeEligible(row.providerModelName())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.MODEL_NOT_VERIFIED,
                    "Model not probed",
                    "OpenCode Zen is admitted for verified-free Chat Completions models only;"
                            + " this model has no current free evidence in the controlled manifest.");
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
        var openCodeZen = ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode());
        var freeOpenCodeZenModel = openCodeZen && openCodeManifest.isFreeEligible(modelName);
        if (freeOpenCodeZenModel || "NONE".equals(profile.authType())) {
            // Free Zen probes always use the shared public credential. Do not read or decrypt a
            // stored organization key for a request that must not use it.
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
            if (openCodeZen) {
                // OpenCode Zen only answers the required streaming CLI envelope; a non-streaming probe
                // body is rejected with 403 FreeTierError (verified live 2026-10-17), so the built-in
                // template is probed through one bounded streaming exchange instead.
                return probeOpenCodeZen(profile, modelName, secret, secretKind, capabilities);
            }
            var body = probeOnce(profile, modelName, secret, secretKind);
            if (body == null) {
                capabilities.put("CHAT_COMPLETIONS", "UNSUPPORTED");
                capabilities.put("SSE_STREAMING", "UNKNOWN");
                return new ProbeResult(false, capabilities, "MODEL_NOT_FOUND");
            }
            capabilities.put("CHAT_COMPLETIONS", isValidChatCompletion(body) ? "VERIFIED" : "UNSUPPORTED");
            capabilities.put("USAGE", hasValidUsage(body) ? "VERIFIED" : "UNSUPPORTED");
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
        var authenticated = (secret != null && !"NONE".equals(profile.authType()))
                || ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode());
        var current = target;
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final ProviderControlPlaneTransport.Result response;
            try {
                response = transport.post(current,
                        probeHeaders(profile, userAgent, secret, secretKind, "application/json",
                                modelName, current),
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
     * OpenCode Zen CLI-posture probe (2026-10-17). The free tier only answers the required streaming
     * CLI envelope ({@code stream=true} + {@code stream_options.include_usage} + the reserved tools);
     * a non-streaming probe body is rejected with {@code 403 FreeTierError} (verified live against
     * {@code https://opencode.ai/inference/v1}). Because of that, the built-in template is probed
     * with exactly one bounded streaming exchange and every capability is derived from the observed
     * SSE, reusing the same legality rules as the bounded streaming probe.
     */
    ProbeResult probeOpenCodeZen(
            com.aicostops.providerhub.domain.ProviderConnection profile, String modelName,
            String secret, String secretKind, Map<String, String> capabilities) throws Exception {
        var body = openCodeZenProbeBody(modelName);
        var target = join(profile.baseUrl(), profile.completionPath());
        endpointValidator.validateEndpoint(target);
        var origin = URI.create(target);
        var userAgent = ProviderTransportSupport.serverUserAgent(profile.userAgent(),
                profile.templateCode());
        var current = target;
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final ProviderControlPlaneTransport.Result response;
            try {
                response = transport.post(current,
                        probeHeaders(profile, userAgent, secret, secretKind, "text/event-stream",
                                modelName, current),
                        body, profile.connectTimeoutMs(), profile.responseTimeoutMs());
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
                // Never forward the Zen credential across origins without re-validation.
                if (!sameOrigin(origin, resolved)) {
                    throw new ProbeHttpStatus("ENDPOINT_BLOCKED");
                }
                current = resolved.toString();
                continue;
            }
            if (status == 401 || status == 403) {
                throw new ProbeHttpStatus("AUTHENTICATION_FAILED");
            }
            if (status == 404) {
                throw new ProbeHttpStatus("MODEL_NOT_FOUND");
            }
            if (status == 429) {
                // A quota-exhausted free tier is a normal bounded outcome, not a Provider fault, but
                // it means no capability can be evidenced on this attempt.
                throw new ProbeHttpStatus("RATE_LIMITED");
            }
            if (status < 200 || status >= 300) {
                throw new ProbeHttpStatus("PROVIDER_UNAVAILABLE");
            }
            var contentType = response.firstHeader("content-type");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                throw new ProbeHttpStatus("PROTOCOL_UNSUPPORTED");
            }
            final String text;
            try (var stream = new java.io.ByteArrayInputStream(response.body())) {
                text = readBoundedStream(stream);
            }
            return classifyOpenCodeZenStream(text, capabilities);
        }
        throw new ProbeHttpStatus("PROVIDER_UNAVAILABLE");
    }

    /** The one serialized probe body carrying the required streaming CLI envelope (contract-tested). */
    String openCodeZenProbeBody(String modelName) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("messages", List.of(Map.of("role", "user",
                "content", "Reply with exactly: {\"ok\":true}")));
        payload.put("max_tokens", 16);
        OpenCodeZenProtocolPolicy.applyRequiredChatEnvelope(payload, true);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("OpenCode Zen probe body could not be serialized", ex);
        }
    }

    /**
     * Derives every OpenCode Zen capability from one observed SSE body. {@code CHAT_COMPLETIONS} and
     * {@code SSE_STREAMING} require a legal Chat Completion chunk plus the terminal {@code [DONE]};
     * {@code USAGE} requires an observed usage object with non-negative token counts; and
     * {@code STRUCTURED_JSON} requires the concatenated delta content to parse to {@code {"ok":true}}.
     */
    ProbeResult classifyOpenCodeZenStream(String sseText, Map<String, String> capabilities) {
        var streamed = containsChunk(sseText) && containsDone(sseText);
        capabilities.put("CHAT_COMPLETIONS", streamed ? "VERIFIED" : "UNSUPPORTED");
        capabilities.put("SSE_STREAMING", streamed ? "VERIFIED" : "UNSUPPORTED");
        capabilities.put("USAGE", streamObservedUsage(sseText) ? "VERIFIED" : "UNSUPPORTED");
        capabilities.put("STRUCTURED_JSON", parsesAsOkObject(streamDeltaContent(sseText))
                ? "VERIFIED" : "UNSUPPORTED");
        var pass = "VERIFIED".equals(capabilities.get("CHAT_COMPLETIONS"));
        return new ProbeResult(pass, capabilities, pass ? null : "PROTOCOL_UNSUPPORTED");
    }

    /** Concatenates the {@code delta.content} fragments of a bounded SSE body. */
    private String streamDeltaContent(String sseText) {
        var content = new StringBuilder();
        for (var line : sseText.split("\n")) {
            var payload = ssePayload(line);
            if (payload == null) {
                continue;
            }
            try {
                var node = objectMapper.readTree(payload);
                var choices = node.get("choices");
                if (choices == null || !choices.isArray()) {
                    continue;
                }
                for (var choice : choices) {
                    var delta = choice.get("delta");
                    if (delta == null || !delta.isObject()) {
                        continue;
                    }
                    var piece = delta.get("content");
                    if (piece != null && piece.isString()) {
                        content.append(piece.stringValue());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return content.toString();
    }

    /** True when any SSE data frame carries a valid usage object. */
    private boolean streamObservedUsage(String sseText) {
        for (var line : sseText.split("\n")) {
            var payload = ssePayload(line);
            if (payload != null && hasValidUsage(payload)) {
                return true;
            }
        }
        return false;
    }

    /** The JSON payload of a single {@code data:} SSE line, or null for fields/blank/{@code [DONE]}. */
    private static String ssePayload(String line) {
        var trimmed = line.strip();
        if (!trimmed.startsWith("data:")) {
            return null;
        }
        var payload = trimmed.substring(5).strip();
        if (payload.isEmpty() || payload.equals("[DONE]")) {
            return null;
        }
        return payload;
    }

    private boolean parsesAsOkObject(String content) {
        try {
            var node = objectMapper.readTree(content);
            if (node == null || !node.isObject()) {
                return false;
            }
            var ok = node.get("ok");
            return ok != null && ok.isBoolean() && ok.booleanValue();
        } catch (Exception ex) {
            return false;
        }
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
            var authenticated = (secret != null && !"NONE".equals(profile.authType()))
                    || ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode());
            var userAgent = ProviderTransportSupport.serverUserAgent(profile.userAgent(),
                    profile.templateCode());
            var current = target;
            for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
                final ProviderControlPlaneTransport.Result response;
                try {
                    response = transport.post(current,
                            probeHeaders(profile, userAgent, secret, secretKind, "text/event-stream",
                                    modelName, current),
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
                // 429 is a normal bounded outcome for the Zen free tier (quota exhausted), not proof
                // that the model lacks SSE streaming, so it must not be recorded as UNSUPPORTED.
                if (status == 401 || status == 403 || status == 404 || status == 429) {
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

    /**
     * Reads a bounded SSE body: bounded by bytes, events and a 20s deadline, with an early exit once
     * both a legal Chat Completion chunk and the terminal {@code [DONE]} marker are observed. Both
     * must be protocol-valid, so a structurally invalid chunk never triggers an early exit.
     */
    private String readBoundedStream(java.io.InputStream stream) throws Exception {
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
            if (containsDone(total.toString()) && containsChunk(total.toString())) {
                break;
            }
        }
        return total.toString();
    }

    private String readStreamVerdict(java.io.InputStream stream) throws Exception {
        var text = readBoundedStream(stream);
        if (!containsChunk(text)) {
            return "UNSUPPORTED";
        }
        if (!containsDone(text)) {
            return "UNKNOWN";
        }
        return "VERIFIED";
    }

    private boolean containsDone(String text) {
        try {
            for (var line : text.split("\n")) {
                var trimmed = line.strip();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                var payload = trimmed.substring(5).strip();
                if (payload.equals("[DONE]")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
                if (isLegalStreamingChunk(payload)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * P1-3 bounded legal Chat Completions streaming chunk validation.
     *
    * <p>Mirrors the Gateway streaming parser contract (OpenAI Chat Completions subset):
     * root object, choices array with at least one choice object, integral non-negative int-range index when present,
    * delta object with string role/content when present, string/null finish_reason. Any
     * structurally invalid JSON (for example {"choices":[1]} or {"choices":[{}]}) is never
     * VERIFIED. Optional fields may be absent, but present fields must have the exact wire types.
     * VERIFIED additionally requires a terminal data:[DONE] line (see readStreamVerdict).
     */
    boolean isLegalStreamingChunk(String payload) {
        try {
            if (payload == null || payload.isBlank() || payload.length() > MAX_BODY_BYTES) {
                return false;
            }
            var node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                return false;
            }
            var choices = node.get("choices");
            if (choices == null || choices.isMissingNode() || choices.isNull()
                    || !choices.isArray() || choices.isEmpty()) {
                return false;
            }
            for (var choice : choices) {
                if (choice == null || choice.isMissingNode() || choice.isNull() || !choice.isObject()) {
                    return false;
                }
                var index = choice.get("index");
                if (index != null && !index.isMissingNode() && !index.isNull()) {
                    if (!index.isIntegralNumber()) {
                        return false;
                    }
                    try {
                        var raw = index.asText("");
                        var intValue = new java.math.BigInteger(raw);
                        if (intValue.signum() < 0
                                || intValue.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                            return false;
                        }
                    } catch (Exception invalidIndex) {
                        return false;
                    }
                }
                var delta = choice.get("delta");
                if (delta == null || delta.isMissingNode() || delta.isNull() || !delta.isObject()) {
                    return false;
                }
                var role = delta.get("role");
                if (role != null && !role.isMissingNode() && !role.isNull() && !role.isString()) {
                    return false;
                }
                var content = delta.get("content");
                if (content != null && !content.isMissingNode() && !content.isNull()
                        && !content.isString()) {
                    return false;
                }
                var finish = choice.get("finish_reason");
                if (finish != null && !finish.isMissingNode() && !finish.isNull() && !finish.isString()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean isValidChatCompletion(String body) {
        try {
            if (body == null || body.isBlank() || body.length() > MAX_BODY_BYTES) {
                return false;
            }
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return false;
            }
            var choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return false;
            }
            var first = choices.get(0);
            if (first == null || !first.isObject()) {
                return false;
            }
            var message = first.get("message");
            if (message == null || !message.isObject()) {
                return false;
            }
            var content = message.get("content");
            if (content == null || !content.isString()) {
                return false;
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    boolean hasValidUsage(String body) {
        try {
            if (body == null || body.isBlank() || body.length() > MAX_BODY_BYTES) {
                return false;
            }
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return false;
            }
            var usage = root.get("usage");
            if (usage == null || !usage.isObject()) {
                return false;
            }
            return isNonNegativeNumber(usage.get("prompt_tokens"))
                    && isNonNegativeNumber(usage.get("completion_tokens"))
                    && isNonNegativeNumber(usage.get("total_tokens"));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isNonNegativeNumber(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.isNumber()) {
            return false;
        }
        try {
            return new java.math.BigDecimal(node.asText("")).compareTo(java.math.BigDecimal.ZERO) >= 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private java.util.Map<String, String> probeHeaders(
            com.aicostops.providerhub.domain.ProviderConnection profile, String userAgent,
            String secret, String secretKind, String accept, String modelName, String endpoint) {
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode())) {
            return openCodeZenHeaders.model(URI.create(endpoint),
                    openCodeManifest.isFreeEligible(modelName), secret, accept);
        }
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
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return false;
            }
            var choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return false;
            }
            var first = choices.get(0);
            if (first == null || !first.isObject()) {
                return false;
            }
            var message = first.get("message");
            if (message == null || !message.isObject()) {
                return false;
            }
            var content = message.get("content");
            if (content == null || !content.isString()) {
                return false;
            }
            var inner = objectMapper.readTree(content.stringValue());
            if (inner == null || !inner.isObject()) {
                return false;
            }
            var ok = inner.get("ok");
            return ok != null && ok.isBoolean() && ok.booleanValue();
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
