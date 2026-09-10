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
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ModelDiscoveryService(
            AuthorizationContextService authorizationContexts,
            ProviderConnectionMapper connections,
            ModelDiscoveryMapper mapper,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.connections = connections;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
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

    /** Refreshes observations: upserts seen models, marks absent ones UNAVAILABLE. */
    public List<DiscoveryResponse> refresh(AuthenticatedUser user, long profileId, List<String> observedModelNames) {
        return refreshInternal(user, profileId, observedModelNames, List.of());
    }

    /**
     * Refreshes observations, optionally fetching the live {@code /models}
     * catalog first (bounded, names only — response bodies are never stored).
     * Live I/O happens before the DB transaction is opened.
     */
    public List<DiscoveryResponse> refresh(AuthenticatedUser user, long profileId,
            List<String> observedModelNames, boolean fetchLive) {
        if (!fetchLive) {
            return refreshInternal(user, profileId, observedModelNames, List.of());
        }
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var profile = connections.find(profileId, context.organizationId());
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        var live = fetchLiveModels(profile);
        return refreshInternal(user, profileId, observedModelNames, live);
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
        mapper.upsertObservation(context.organizationId(), profileId, name, name,
                "MANUAL", "AVAILABLE", profile.protocolCode(), "UNKNOWN",
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
        var modelKey = discovery.providerModelName().toLowerCase(java.util.Locale.ROOT);
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

    private List<String> fetchLiveModels(com.aicostops.providerhub.domain.ProviderConnection profile) {
        if (profile.modelsPath() == null || profile.modelsPath().isBlank()) {
            return List.of();
        }
        try {
            var target = joinBase(profile.baseUrl(), profile.modelsPath());
            var client = java.net.http.HttpClient.newBuilder()
                    .proxy(java.net.ProxySelector.of(null))
                    .connectTimeout(java.time.Duration.ofMillis(profile.connectTimeoutMs()))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
                    .timeout(java.time.Duration.ofMillis(profile.responseTimeoutMs()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().length() > 65536) {
                return List.of();
            }
            return parseModelIds(response.body());
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> parseModelIds(String body) {
        var ids = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]{1,200})\"")
                .matcher(body);
        while (matcher.find() && ids.size() < 500) {
            var id = matcher.group(1).strip();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
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
