package com.aicostops.providerhub.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.providerhub.infrastructure.ModelDiscoveryMapper;
import com.aicostops.providerhub.infrastructure.ModelDiscoveryMapper.DiscoveryRow;
import com.aicostops.providerhub.infrastructure.ProviderConnectionMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model discovery / promotion (M18 V3). Discovery rows are observations;
 * only promotion creates routable catalog truth behind pricing/routing gates.
 */
@Service
public class ModelDiscoveryService {

    static final String EMPTY_CAPABILITIES = "{\"capabilities\": []}";
    static final String CHAT_CAPABILITIES = "{\"capabilities\": [\"CHAT_COMPLETIONS\"]}";

    private final AuthorizationContextService authorizationContexts;
    private final ProviderConnectionMapper connections;
    private final ModelDiscoveryMapper mapper;
    private final com.aicostops.providerhub.infrastructure.ProbeCredentialMapper credentials;
    private final CustomEndpointValidator endpointValidator;
    private final ProviderControlPlaneTransport transport;
    private final AuditService audit;
    private final Clock clock;
    private final ModelDiscoveryService self;
    private final String providerKek;
    private final OpenCodeModelManifest openCodeManifest;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ModelDiscoveryService(
            AuthorizationContextService authorizationContexts,
            ProviderConnectionMapper connections,
            ModelDiscoveryMapper mapper,
            com.aicostops.providerhub.infrastructure.ProbeCredentialMapper credentials,
            CustomEndpointValidator endpointValidator,
            ProviderControlPlaneTransport transport,
            AuditService audit,
            Clock clock,
            @Lazy ModelDiscoveryService self,
            @org.springframework.beans.factory.annotation.Value("${aicostops.gateway.provider-kek-v1:}") String providerKek,
            OpenCodeModelManifest openCodeManifest) {
        this.authorizationContexts = authorizationContexts;
        this.connections = connections;
        this.mapper = mapper;
        this.credentials = credentials;
        this.endpointValidator = endpointValidator;
        this.transport = transport;
        this.audit = audit;
        this.clock = clock;
        this.self = self;
        this.providerKek = providerKek;
        this.openCodeManifest = openCodeManifest;
    }

    public List<DiscoveryResponse> list(AuthenticatedUser user, long profileId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        return mapper.listByProfile(context.organizationId(), profileId).stream()
                .map(this::response).toList();
    }

    /**
     * Legacy client-authored refresh entry: production live provenance must come from the
     * Provider. Client modelNames can no longer become LIVE_DISCOVERY; use
     * POST .../models/manual for intentional registration. Rejected with zero mutation.
     */
    public List<DiscoveryResponse> refresh(AuthenticatedUser user, long profileId, List<String> observedModelNames) {
        throw validationFailed("Model refresh requires live Provider discovery; use manual registration for declared models.");
    }

    /**
     * Refreshes observations, optionally fetching the live {@code /models} catalog first
     * (bounded, names only — response bodies are never stored). Live I/O happens before the DB
     * transaction is opened, but only after PROVIDER_ACCOUNT_MANAGE is enforced: live discovery
     * performs outbound Provider I/O and must never be triggerable by a read-only user. A live
     * failure never masquerades as an empty catalog; only a successful snapshot may mark absent
     * models UNAVAILABLE.
     */
    public List<DiscoveryResponse> refresh(AuthenticatedUser user, long profileId,
            List<String> observedModelNames, boolean fetchLive) {
        if (!fetchLive) {
            throw validationFailed("Model refresh requires live Provider discovery; use manual registration for declared models.");
        }
        // P1: MANAGE before any Provider I/O (readers must not trigger external side effects).
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        // Throws PROVIDER_UNAVAILABLE / ENDPOINT_BLOCKED on failure; callers must not mark
        // existing AVAILABLE rows UNAVAILABLE in that case. A successful live snapshot is the
        // only source of truth here: client-supplied modelNames are ignored on the live path
        // (use POST .../models/manual for intentional manual registration).
        var live = fetchLiveModelsOrThrow(profile);
        return self.refreshFromLiveSnapshot(user, profileId, live);
    }

    /**
     * Persists one successful live {@code /models} snapshot: upserts every observed ID as
     * LIVE_DISCOVERY/AVAILABLE, then marks previously AVAILABLE rows absent from the snapshot
     * UNAVAILABLE. Must only be called after a successful snapshot; live failures must never
     * reach here.
     */
    @Transactional
    List<DiscoveryResponse> refreshFromLiveSnapshot(AuthenticatedUser user, long profileId,
            List<String> liveModels) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        var now = clock.instant();
        var seen = new HashSet<String>();
        for (var raw : liveModels == null ? List.<String>of() : liveModels) {
            var name = raw == null ? "" : raw.strip();
            if (name.isEmpty() || name.length() > 200 || !seen.add(name)) {
                continue;
            }
            // P1-4 OpenCode manifest: live /models proves availability only. Per-model protocol
            // and pricing come from the server-owned manifest; unknown stays UNKNOWN and can
            // never be guessed into the generic Chat adapter. Custom connections keep the
            // explicit connection-level Chat contract.
            var protocol = profile.protocolCode();
            var pricing = "UNKNOWN";
            if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode())) {
                protocol = openCodeManifest.protocolFor(name);
                pricing = openCodeManifest.pricingFor(name);
            }
            mapper.upsertObservation(context.organizationId(), profileId, name, name,
                    "LIVE_DISCOVERY", "AVAILABLE", protocol, pricing,
                    EMPTY_CAPABILITIES, EMPTY_CAPABILITIES, now);
        }
        for (var row : mapper.listByProfile(context.organizationId(), profileId)) {
            if ("AVAILABLE".equals(row.availability()) && !seen.contains(row.providerModelName())) {
                mapper.markOneUnavailable(context.organizationId(), profileId, row.providerModelName(), now);
            }
        }
        audit.append("PROVIDER_MODEL_DISCOVERED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", profileId,
                Map.of("observed", seen.size(), "protocol", profile.protocolCode(),
                        "source", "LIVE_SNAPSHOT"));
        return list(user, profileId);
    }

    @Transactional
    List<DiscoveryResponse> refreshInternal(AuthenticatedUser user, long profileId,
            List<String> observedModelNames, List<String> liveModels) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        var now = clock.instant();
        var seen = new HashSet<String>();
        for (var raw : liveModels) {
            var name = raw == null ? "" : raw.strip();
            if (!name.isEmpty() && name.length() <= 200) seen.add(name);
        }
        for (var raw : observedModelNames == null ? List.<String>of() : observedModelNames) {
            var name = raw == null ? "" : raw.strip();
            if (name.isEmpty() || name.length() > 200 || !seen.add(name)) {
                continue;
            }
            mapper.upsertObservation(context.organizationId(), profileId, name, name,
                    "LIVE_DISCOVERY", "AVAILABLE", profile.protocolCode(), "UNKNOWN",
                    EMPTY_CAPABILITIES, EMPTY_CAPABILITIES, now);
        }
        for (var row : mapper.listByProfile(context.organizationId(), profileId)) {
            if ("AVAILABLE".equals(row.availability()) && !seen.contains(row.providerModelName())) {
                mapper.markOneUnavailable(context.organizationId(), profileId, row.providerModelName(), now);
            }
        }
        audit.append("PROVIDER_MODEL_DISCOVERED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", profileId,
                Map.of("observed", seen.size(), "protocol", profile.protocolCode()));
        return list(user, profileId);
    }

    /** Registers a model observation manually when live discovery is unavailable. */
    @Transactional
    public DiscoveryResponse registerManual(AuthenticatedUser user, long profileId, String modelName) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        var name = modelName == null ? "" : modelName.strip();
        if (name.isEmpty() || name.length() > 200) {
            throw validationFailed("Model name is required.");
        }
        var now = clock.instant();
        // P1-4 OpenCode manifest also applies to manual observations: never guess Chat
        // compatibility from the connection-level protocol.
        var manualProtocol = profile.protocolCode();
        var manualPricing = "UNKNOWN";
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode())) {
            manualProtocol = openCodeManifest.protocolFor(name);
            manualPricing = openCodeManifest.pricingFor(name);
        }
        mapper.upsertObservation(context.organizationId(), profileId, name, name,
                "MANUAL", "AVAILABLE", manualProtocol, manualPricing,
                EMPTY_CAPABILITIES, EMPTY_CAPABILITIES, now);
        var created = mapper.listByProfile(context.organizationId(), profileId).stream()
                .filter(r -> r.providerModelName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Manual model observation was not persisted"));
        audit.append("PROVIDER_MODEL_DISCOVERED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", profileId, Map.of("model", name, "source", "MANUAL"));
        return response(created);
    }

    /**
     * Promotes a discovered model to routable catalog truth: creates (or links)
     * the org-private logical model plus the exact-account provider_model.
     * Pricing and routing gates still apply before production dispatch.
     */
    @Transactional
    public PromotionResponse promote(AuthenticatedUser user, long profileId, long discoveryId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        var discovery = mapper.find(discoveryId, context.organizationId());
        if (discovery == null || discovery.providerConnectionProfileId() != profileId) {
            throw notFound("Model discovery was not found.");
        }
        if (!"AVAILABLE".equals(discovery.availability())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.MODEL_NOT_VERIFIED,
                    "Model not promotable", "Only AVAILABLE observations can be promoted.");
        }
        if (!"OPENAI_CHAT_COMPLETIONS".equals(discovery.protocolCode())
                || !"OPENAI_CHAT_COMPLETIONS".equals(profile.protocolCode())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.MODEL_NOT_VERIFIED,
                    "Model not promotable", "Only OPENAI_CHAT_COMPLETIONS models can be promoted.");
        }
        // P1-4 OpenCode manifest gate: promotion requires controlled Chat compatibility plus
        // successful probe plus verified CHAT. Unknown/unsupported stays AVAILABLE but never
        // becomes routable via the Chat adapter. Custom connections skip the manifest because
        // their connection itself is the explicit Chat contract.
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(profile.templateCode())
                && !openCodeManifest.isChatCompatible(discovery.providerModelName())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.MODEL_NOT_VERIFIED,
                    "Model not promotable",
                    "OpenCode model is not classified for Chat Completions in the controlled manifest.");
        }
        if (!"PASS".equals(discovery.lastProbeStatus())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.MODEL_NOT_VERIFIED,
                    "Model not promotable", "Only probed models can be promoted.");
        }
        if (!hasVerifiedChatCompletions(discovery.verifiedCapabilitiesJson())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.MODEL_NOT_VERIFIED,
                    "Model not promotable", "Only models with verified Chat Completions can be promoted.");
        }
        var modelKey = discovery.providerModelName().toLowerCase(java.util.Locale.ROOT);
        if (!modelKey.matches("[a-z0-9][a-z0-9._-]{0,99}")) {
            throw validationFailed("Model name cannot become a routable model key.");
        }
        if (mapper.findGlobalLogicalModel(modelKey) != null) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Model key collision", "A private model must not shadow a global model key.");
        }
        var now = clock.instant();
        Long logicalModelId = mapper.findPrivateLogicalModel(context.organizationId(), modelKey);
        if (logicalModelId == null) {
            mapper.insertPrivateLogicalModel(modelKey, context.organizationId(),
                    discovery.providerModelName(), CHAT_CAPABILITIES, 8192, 131072, now);
            logicalModelId = mapper.lastInsertId();
        }
        var accountCode = connections.findActiveAccountProviderCode(
                context.organizationId(), profile.providerAccountId());
        if (accountCode == null) {
            throw notFound("Provider account is not available.");
        }
        Long providerModelId = mapper.findPrivateProviderModel(
                context.organizationId(), profile.providerAccountId(), accountCode,
                discovery.providerModelName());
        if (providerModelId == null) {
            mapper.insertPrivateProviderModel(accountCode, context.organizationId(),
                    profile.providerAccountId(), logicalModelId, discovery.providerModelName(),
                    discovery.verifiedCapabilitiesJson() == null
                            ? EMPTY_CAPABILITIES : discovery.verifiedCapabilitiesJson(),
                    now);
            providerModelId = mapper.lastInsertId();
        }
        audit.append("PROVIDER_MODEL_PROMOTED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", profileId,
                Map.of("model", discovery.providerModelName(), "logicalModelId", logicalModelId,
                        "providerModelId", providerModelId));
        return new PromotionResponse(logicalModelId, providerModelId, false, false);
    }

    private DiscoveryResponse response(DiscoveryRow row) {
        return new DiscoveryResponse(row.id(), row.providerModelName(), row.displayName(),
                row.source(), row.availability(), row.protocolCode(), row.pricingClassification(),
                row.lastSeenAt(), row.lastProbedAt(), row.lastProbeStatus(), row.lastProbeErrorCode());
    }

    boolean hasVerifiedChatCompletions(String verifiedCapabilitiesJson) {
        if (verifiedCapabilitiesJson == null || verifiedCapabilitiesJson.isBlank()) {
            return false;
        }
        try {
            var root = DISCOVERY_MAPPER.readTree(verifiedCapabilitiesJson);
            if (root == null || !root.isObject()) {
                return false;
            }
            var caps = root.get("capabilities");
            if (caps == null || !caps.isArray()) {
                return false;
            }
            for (var item : caps) {
                if (item != null && item.isString()
                        && "CHAT_COMPLETIONS".equals(item.stringValue())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * P1: bounded authenticated live discovery sharing the unified Provider transport policy.
     * Applies base URL / models path / DIRECT_ONLY|DIRECT_PUBLIC_ONLY / credential (BEARER /
     * API_KEY_HEADER / NONE) / server-owned User-Agent / timeouts / SSRF + redirect policy /
     * body bound. Never swallows a Provider failure into an empty list.
     */
    /**
     * Bounded authenticated live discovery over the shared control-plane transport (single DNS
     * resolution bound to the actual connect per hop, proxy disabled, bounded body, manual
     * redirects with per-hop SSRF re-validation and same-origin secret policy). Never swallows a
     * Provider failure into an empty list.
     */
    List<String> fetchLiveModelsOrThrow(com.aicostops.providerhub.domain.ProviderConnection profile) {
        if (profile.modelsPath() == null || profile.modelsPath().isBlank()) {
            throw validationFailed("Provider models path is not configured; use manual registration.");
        }
        var target = ProviderTransportSupport.joinBase(profile.baseUrl(), profile.modelsPath());
        endpointValidator.validateEndpoint(target);
        var origin = java.net.URI.create(target);
        var secret = resolveSecret(profile);
        var authenticated = secret != null;
        var userAgent = ProviderTransportSupport.serverUserAgent(profile.userAgent(),
                profile.templateCode());
        var current = target;
        for (var hop = 0; hop <= ProviderTransportSupport.MAX_REDIRECTS; hop++) {
            final ProviderControlPlaneTransport.Result response;
            try {
                response = transport.get(current, discoveryHeaders(profile, userAgent, secret),
                        profile.connectTimeoutMs(), profile.responseTimeoutMs());
            } catch (ProviderControlPlaneTransport.TransportException ex) {
                throw mapTransportFailure(ex);
            }
            var status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                var location = response.firstHeader("location");
                if (location.isBlank() || hop == ProviderTransportSupport.MAX_REDIRECTS) {
                    throw providerUnavailable("Provider discovery redirect was rejected.");
                }
                final java.net.URI resolved;
                try {
                    resolved = ProviderTransportSupport.resolveRedirect(current, location);
                } catch (IllegalArgumentException ex) {
                    throw providerUnavailable("Provider discovery redirect was rejected.");
                }
                endpointValidator.validateRedirectTarget(resolved.toString());
                if (authenticated && !ProviderTransportSupport.sameOrigin(origin, resolved)) {
                    throw endpointBlocked("Authenticated discovery must not follow a cross-origin redirect.");
                }
                current = resolved.toString();
                continue;
            }
            if (status == 401 || status == 403) {
                throw authFailed("Provider discovery authentication failed.");
            }
            if (status == 429) {
                throw providerUnavailable("Provider discovery was rate limited.");
            }
            if (status < 200 || status >= 300) {
                throw providerUnavailable("Provider discovery is unavailable.");
            }
            return parseModelIds(response.bodyAsUtf8());
        }
        throw providerUnavailable("Provider discovery is unavailable.");
    }

    private String resolveSecret(com.aicostops.providerhub.domain.ProviderConnection profile) {
        if ("NONE".equals(profile.authType())) {
            return null;
        }
        var credential = credentials.findActive(profile.organizationId(), profile.providerAccountId());
        if (credential == null) {
            throw authFailed("Provider credential is not available.");
        }
        if (providerKek == null || providerKek.isBlank()) {
            throw providerUnavailable("Provider discovery is unavailable.");
        }
        try {
            return new com.aicostops.gatewayadmin.security.ProviderCredentialEncryptor(providerKek).decrypt(
                    credential.ciphertext(), credential.nonce(), profile.organizationId(),
                    profile.providerAccountId(), credential.credentialType(),
                    credential.encryptionKeyVersion());
        } catch (Exception ex) {
            throw authFailed("Provider discovery authentication failed.");
        }
    }

    private java.util.Map<String, String> discoveryHeaders(
            com.aicostops.providerhub.domain.ProviderConnection profile, String userAgent,
            String secret) {
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", userAgent);
        if (secret == null || "NONE".equals(profile.authType())) {
            return headers;
        }
        if ("API_KEY_HEADER".equals(profile.authType())) {
            headers.put(profile.authHeaderName(), secret);
        } else {
            headers.put("Authorization", "Bearer " + secret);
        }
        return headers;
    }

    private DomainException mapTransportFailure(ProviderControlPlaneTransport.TransportException ex) {
        return switch (ex.errorCode()) {
            case ProviderControlPlaneTransport.TransportException.CONNECTION_TIMEOUT ->
                providerUnavailable("Provider discovery timed out.");
            case ProviderControlPlaneTransport.TransportException.DNS_FAILED ->
                providerUnavailable("Provider discovery DNS failed.");
            case ProviderControlPlaneTransport.TransportException.TLS_FAILED ->
                providerUnavailable("Provider discovery TLS failed.");
            default -> providerUnavailable("Provider discovery is unavailable.");
        };
    }

    private DomainException authFailed(String detail) {
        return new DomainException(HttpStatus.BAD_GATEWAY, ProblemCode.PROVIDER_UNAVAILABLE,
                "Provider authentication failed", detail);
    }

    private DomainException endpointBlocked(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.ENDPOINT_BLOCKED,
                "Provider endpoint blocked", detail);
    }

    private DomainException providerUnavailable(String detail) {
        return new DomainException(HttpStatus.BAD_GATEWAY, ProblemCode.PROVIDER_UNAVAILABLE,
                "Provider unavailable", detail);
    }

    @Deprecated
    private List<String> fetchLiveModels(com.aicostops.providerhub.domain.ProviderConnection profile) {
        return fetchLiveModelsOrThrow(profile);
    }

    private static final tools.jackson.databind.ObjectMapper DISCOVERY_MAPPER =
            new tools.jackson.databind.ObjectMapper();

    List<String> parseModelIds(String body) {
        if (body == null || body.isBlank() || body.length() > ProviderTransportSupport.MAX_BODY_BYTES) {
            throw providerUnavailable("Provider discovery returned a malformed catalog.");
        }
        try {
            var root = DISCOVERY_MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw providerUnavailable("Provider discovery returned a malformed catalog.");
            }
            var data = root.get("data");
            if (data == null || !data.isArray()) {
                throw providerUnavailable("Provider discovery returned a malformed catalog.");
            }
            // P1-2 fail-closed: a successful catalog larger than the supported bound must never
            // become a partial snapshot that marks unseen rows UNAVAILABLE. Raw array size is
            // checked (not unique count) so 500 unique + unbounded duplicates cannot bypass the
            // bound. Zero discovery mutations occur on overflow; callers preserve availability.
            if (data.size() > ProviderTransportSupport.MAX_MODEL_IDS) {
                throw providerUnavailable("Provider discovery catalog exceeds supported bound.");
            }
            var ids = new java.util.ArrayList<String>();
            var seen = new java.util.HashSet<String>();
            for (var entry : data) {
                if (entry == null || !entry.isObject()) {
                    throw providerUnavailable("Provider discovery returned a malformed catalog.");
                }
                var idNode = entry.get("id");
                if (idNode == null || !idNode.isString()) {
                    throw providerUnavailable("Provider discovery returned a malformed catalog.");
                }
                var id = idNode.stringValue() == null ? "" : idNode.stringValue().strip();
                if (id.isEmpty() || id.length() > 200) {
                    throw providerUnavailable("Provider discovery returned a malformed catalog.");
                }
                if (seen.add(id)) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw providerUnavailable("Provider discovery returned a malformed catalog.");
        }
    }

    private static String joinBase(String base, String path) {
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + (path.startsWith("/") ? path : "/" + path);
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Model discovery validation failed", detail);
    }

    private DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Model discovery not found", detail);
    }

    public record DiscoveryResponse(
            long id, String providerModelName, String displayName, String source,
            String availability, String protocolCode, String pricingClassification,
            Instant lastSeenAt, Instant lastProbedAt, String lastProbeStatus, String lastProbeErrorCode) {
    }

    public record PromotionResponse(
            long logicalModelId, long providerModelId, boolean pricingReady, boolean routingReady) {
    }
}
