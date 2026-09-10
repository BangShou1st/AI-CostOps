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
    private static final String SYNTHETIC_BODY =
            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\"," +
            "\"content\":\"Reply with exactly: {\\\"ok\\\":true}\"}]," +
            "\"max_tokens\":16,\"response_format\":{\"type\":\"json_object\"},\"stream\":false}";

    private final AuthorizationContextService authorizationContexts;
    private final ProviderConnectionMapper connections;
    private final ModelDiscoveryMapper discovery;
    private final ProbeCredentialMapper credentials;
    private final CustomEndpointValidator endpointValidator;
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
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${aicostops.gateway.provider-kek-v1:}") String providerKek) {
        this.authorizationContexts = authorizationContexts;
        this.connections = connections;
        this.discovery = discovery;
        this.credentials = credentials;
        this.endpointValidator = endpointValidator;
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
                return new ProbeResult(false, capabilities, "MODEL_NOT_FOUND");
            }
            capabilities.put("CHAT_COMPLETIONS", body.contains("\"choices\"") ? "VERIFIED" : "UNSUPPORTED");
            capabilities.put("USAGE", body.contains("\"usage\"") ? "VERIFIED" : "UNSUPPORTED");
            capabilities.put("STRUCTURED_JSON", looksStructured(body) ? "VERIFIED" : "UNSUPPORTED");
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

    private String probeOnce(com.aicostops.providerhub.domain.ProviderConnection profile,
            String modelName, String secret, String secretKind) throws Exception {
        var client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(Duration.ofMillis(profile.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var target = join(profile.baseUrl(), profile.completionPath());
        endpointValidator.validateEndpoint(target);
        var userAgent = profile.userAgent() == null ? "AI-CostOps-Probe/3.0" : profile.userAgent();
        var payload = SYNTHETIC_BODY.formatted(escapeJson(modelName));
        for (var hop = 0; hop <= MAX_REDIRECTS; hop++) {
            var builder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(profile.responseTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            applyAuth(builder, profile, secret, secretKind);
            var response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                var location = response.headers().firstValue("location").orElse("");
                if (location.isBlank() || hop == MAX_REDIRECTS) {
                    throw new ProbeHttpStatus("ENDPOINT_BLOCKED");
                }
                endpointValidator.validateRedirectTarget(location);
                target = location;
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
            var body = response.body() == null ? "" : response.body();
            return body.length() > MAX_BODY_BYTES ? body.substring(0, MAX_BODY_BYTES) : body;
        }
        throw new ProbeHttpStatus("PROVIDER_UNAVAILABLE");
    }

    private void applyAuth(HttpRequest.Builder builder,
            com.aicostops.providerhub.domain.ProviderConnection profile, String secret, String secretKind) {
        if ("NONE".equals(profile.authType()) || secret == null) {
            return;
        }
        if ("BEARER".equals(profile.authType()) || "BEARER_TOKEN".equals(secretKind)) {
            builder.header("Authorization", "Bearer " + secret);
        } else {
            builder.header(profile.authHeaderName(), secret);
        }
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
