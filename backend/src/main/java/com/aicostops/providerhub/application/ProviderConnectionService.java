package com.aicostops.providerhub.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.providerhub.api.ProviderHubDtos.ConnectionResponse;
import com.aicostops.providerhub.api.ProviderHubDtos.CreateConnectionRequest;
import com.aicostops.providerhub.api.ProviderHubDtos.ProbeResponse;
import com.aicostops.providerhub.api.ProviderHubDtos.TemplateResponse;
import com.aicostops.providerhub.api.ProviderHubDtos.UpdateConnectionRequest;
import com.aicostops.providerhub.domain.ProviderConnection;
import com.aicostops.providerhub.infrastructure.ProviderConnectionMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned Provider Connection Profiles (M18 V3).
 *
 * <p>ACTIVE/RETIRED rows are immutable; only DRAFT is editable. Activation
 * retires the previous ACTIVE version in the same transaction. Provider I/O
 * (probe) never runs inside a DB transaction.
 */
@Service
public class ProviderConnectionService {

    private final AuthorizationContextService authorizationContexts;
    private final ProviderConnectionMapper mapper;
    private final ProviderTemplateRegistry templates;
    private final CustomEndpointValidator endpointValidator;
    private final com.aicostops.providerhub.infrastructure.ProbeCredentialMapper credentials;
    private final ProviderControlPlaneTransport transport;
    private final AuditService audit;
    private final Clock clock;
    private final String providerKek;
    private final ProviderConnectionService self;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ProviderConnectionService(
            AuthorizationContextService authorizationContexts,
            ProviderConnectionMapper mapper,
            ProviderTemplateRegistry templates,
            CustomEndpointValidator endpointValidator,
            com.aicostops.providerhub.infrastructure.ProbeCredentialMapper credentials,
            ProviderControlPlaneTransport transport,
            AuditService audit,
            Clock clock,
            @Lazy ProviderConnectionService self,
            @org.springframework.beans.factory.annotation.Value("${aicostops.gateway.provider-kek-v1:}") String providerKek) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.templates = templates;
        this.endpointValidator = endpointValidator;
        this.credentials = credentials;
        this.transport = transport;
        this.audit = audit;
        this.clock = clock;
        this.self = self;
        this.providerKek = providerKek;
    }

    public List<TemplateResponse> templates(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return templates.list().stream()
                .map(t -> new TemplateResponse(t.code(), t.name(), t.connectionKind(),
                        t.protocolCode(), t.baseUrl(), t.networkPolicy(), t.authType(),
                        List.copyOf(t.lockedFields())))
                .toList();
    }

    public PageResponse<ConnectionResponse> list(AuthenticatedUser user, PageRequest page) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var total = mapper.count(context.organizationId());
        var rows = mapper.findPage(context.organizationId(),
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(rows.stream().map(this::response).toList(), page, total);
    }

    public ConnectionResponse get(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return response(require(context.organizationId(), id));
    }

    /**
     * Creates a DRAFT profile. External network destination preflight (DNS) runs here, outside
     * any DB transaction; the short state-change transaction below performs only deterministic
     * validation plus writes. Runtime SSRF authority stays with the transport resolver at
     * actual connect time.
     */
    public ConnectionResponse create(AuthenticatedUser user, CreateConnectionRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var preflightUrl = createPreflightUrl(context.organizationId(), request);
        if (preflightUrl != null) {
            endpointValidator.validateSyntax(preflightUrl);
            endpointValidator.resolvePublicAddresses(preflightUrl);
        }
        return self.createTx(user, request);
    }

    /** Effective user-supplied base URL needing external preflight, or null when the tx alone
     * decides (missing account/URL reported deterministically inside the transaction). Built-in
     * server-owned endpoints need no user-input preflight. */
    private String createPreflightUrl(long organizationId, CreateConnectionRequest request) {
        if (request.providerAccountId() == null || request.baseUrl() == null
                || request.baseUrl().isBlank()) {
            return null;
        }
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(request.templateCode())) {
            return null;
        }
        var accountCode = mapper.findActiveAccountProviderCode(organizationId, request.providerAccountId());
        if (!ProviderTemplateRegistry.CUSTOM_OPENAI_COMPATIBLE.equals(accountCode)) {
            return null;
        }
        return request.baseUrl();
    }

    @Transactional
    public ConnectionResponse createTx(AuthenticatedUser user, CreateConnectionRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        if (request.providerAccountId() == null) {
            throw validationFailed("Provider account is required.");
        }
        var accountCode = mapper.findActiveAccountProviderCode(
                context.organizationId(), request.providerAccountId());
        if (accountCode == null) {
            throw notFound("Provider account is not available.");
        }
        var templateCode = request.templateCode();
        final String kind;
        final String protocol;
        final String baseUrl;
        final String completionPath;
        final String modelsPath;
        final String authType;
        final String authHeaderName;
        final String networkPolicy;
        final String userAgent;
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(templateCode)) {
            if (!ProviderTemplateRegistry.OPENCODE_ZEN.equals(accountCode)) {
                throw validationFailed("Built-in OpenCode Zen connections require an OPENCODE_ZEN provider account.");
            }
            var template = templates.require(templateCode);
            rejectBuiltinOverride(request);
            kind = "BUILTIN";
            protocol = template.protocolCode();
            baseUrl = template.baseUrl();
            completionPath = template.completionPath();
            modelsPath = template.modelsPath();
            authType = template.authType();
            authHeaderName = null;
            networkPolicy = template.networkPolicy();
            userAgent = template.userAgent();
        } else {
            if (templateCode != null && !ProviderTemplateRegistry.CUSTOM_OPENAI_COMPATIBLE.equals(templateCode)) {
                throw validationFailed("Unknown provider template.");
            }
            if (!ProviderTemplateRegistry.CUSTOM_OPENAI_COMPATIBLE.equals(accountCode)) {
                throw validationFailed(
                        "Custom connections require a CUSTOM_OPENAI_COMPATIBLE provider account.");
            }
            kind = "CUSTOM";
            protocol = ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS;
            baseUrl = required(request.baseUrl(), "Base URL is required.");
            completionPath = request.completionPath() == null ? "/chat/completions" : request.completionPath();
            modelsPath = request.modelsPath();
            authType = request.authType() == null ? "BEARER" : request.authType();
            if (!authType.equals("BEARER") && !authType.equals("API_KEY_HEADER") && !authType.equals("NONE")) {
                throw validationFailed("Unsupported auth type.");
            }
            authHeaderName = authType.equals("API_KEY_HEADER") ? required(request.authHeaderName(), "API key header name is required.") : null;
            endpointValidator.validateAuthHeaderName(authType, request.authHeaderName());
            networkPolicy = "DIRECT_PUBLIC_ONLY";
            userAgent = request.userAgent();
            // DNS preflight ran outside the transaction; re-check only deterministic shape here.
            endpointValidator.validateSyntax(baseUrl);
        }
        var version = mapper.nextVersion(context.organizationId(), request.providerAccountId());
        var now = clock.instant();
        mapper.insert(context.organizationId(), request.providerAccountId(), version, kind,
                ProviderTemplateRegistry.OPENCODE_ZEN.equals(templateCode) ? templateCode : null,
                protocol, baseUrl, completionPath, modelsPath, authType, authHeaderName,
                networkPolicy, userAgent,
                request.connectTimeoutMs() == null ? 5000 : request.connectTimeoutMs(),
                request.responseTimeoutMs() == null ? 60000 : request.responseTimeoutMs(),
                "DRAFT", context.organizationMemberId(), now, null);
        var created = require(context.organizationId(), mapper.lastInsertId());
        audit.append("PROVIDER_CONNECTION_CREATED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", created.id(),
                Map.of("accountId", created.providerAccountId(), "version", created.version(),
                        "kind", created.connectionKind(), "status", created.status()));
        return response(created);
    }

    /**
     * DRAFT-only update. External network destination preflight (DNS) for a changed base URL
     * runs here, outside any DB transaction; the short state-change transaction below performs
     * only deterministic validation plus writes.
     */
    public ConnectionResponse updateDraft(AuthenticatedUser user, long id, UpdateConnectionRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var current = require(context.organizationId(), id);
        if (!"DRAFT".equals(current.status())) {
            throw stateConflict("Only DRAFT connections are editable.");
        }
        if (request.baseUrl() != null && !request.baseUrl().equals(current.baseUrl())) {
            endpointValidator.validateSyntax(request.baseUrl());
            endpointValidator.resolvePublicAddresses(request.baseUrl());
        }
        return self.updateDraftTx(user, id, request);
    }

    @Transactional
    public ConnectionResponse updateDraftTx(AuthenticatedUser user, long id, UpdateConnectionRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var current = requireForUpdate(context.organizationId(), id);
        if (!"DRAFT".equals(current.status())) {
            throw stateConflict("Only DRAFT connections are editable.");
        }
        if (ProviderTemplateRegistry.OPENCODE_ZEN.equals(current.templateCode())) {
            if (request.baseUrl() != null || request.authType() != null
                    || request.completionPath() != null || request.modelsPath() != null
                    || request.userAgent() != null) {
                throw validationFailed("Built-in template fields are server-owned.");
            }
        }
        var baseUrl = request.baseUrl() == null ? current.baseUrl() : request.baseUrl();
        var authType = request.authType() == null ? current.authType() : request.authType();
        var authHeaderName = request.authHeaderName() == null ? current.authHeaderName() : request.authHeaderName();
        if (!authType.equals("BEARER") && !authType.equals("API_KEY_HEADER") && !authType.equals("NONE")) {
            throw validationFailed("Unsupported auth type.");
        }
        // P2: validate the effective header name, not the raw request field, so editing an
        // unrelated draft field without resending authHeaderName keeps the retained value valid.
        endpointValidator.validateAuthHeaderName(authType, authHeaderName);
        if (!baseUrl.equals(current.baseUrl())) {
            // DNS preflight ran outside the transaction; re-check only deterministic shape here.
            endpointValidator.validateSyntax(baseUrl);
        }
        mapper.updateDraft(id, context.organizationId(), baseUrl,
                request.completionPath() == null ? current.completionPath() : request.completionPath(),
                request.modelsPath() == null ? current.modelsPath() : request.modelsPath(),
                authType, authType.equals("API_KEY_HEADER") ? authHeaderName : null,
                request.userAgent() == null ? current.userAgent() : request.userAgent(),
                request.connectTimeoutMs() == null ? current.connectTimeoutMs() : request.connectTimeoutMs(),
                request.responseTimeoutMs() == null ? current.responseTimeoutMs() : request.responseTimeoutMs());
        var updated = require(context.organizationId(), id);
        audit.append("PROVIDER_CONNECTION_REVISION_CREATED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", id, Map.of("version", updated.version(), "status", updated.status()));
        return response(updated);
    }

    /**
     * Activates a DRAFT profile. External network destination preflight (DNS) runs here, outside
     * any DB transaction; the short activation transaction below performs only deterministic
     * validation plus the retire-previous/activate writes.
     */
    public ConnectionResponse activate(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var target = require(context.organizationId(), id);
        if (!"DRAFT".equals(target.status())) {
            throw stateConflict("Only DRAFT connections can be activated.");
        }
        endpointValidator.validateSyntax(target.baseUrl());
        endpointValidator.resolvePublicAddresses(target.baseUrl());
        return self.activateTx(user, id);
    }

    @Transactional
    public ConnectionResponse activateTx(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var target = requireForUpdate(context.organizationId(), id);
        if (!"DRAFT".equals(target.status())) {
            throw stateConflict("Only DRAFT connections can be activated.");
        }
        // DNS preflight ran outside the transaction; re-check only deterministic shape here.
        endpointValidator.validateSyntax(target.baseUrl());
        var previous = mapper.findActiveForUpdate(context.organizationId(), target.providerAccountId());
        var now = clock.instant();
        if (previous != null && previous.id() != target.id()) {
            if (mapper.retireActive(previous.id(), context.organizationId(), now) != 1) {
                throw new IllegalStateException("Previous ACTIVE connection could not be retired");
            }
        }
        if (mapper.activateDraft(target.id(), context.organizationId(), now) != 1) {
            throw new IllegalStateException("DRAFT connection could not be activated");
        }
        var activated = require(context.organizationId(), id);
        audit.append("PROVIDER_CONNECTION_ACTIVATED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", id,
                Map.of("version", activated.version(),
                        "retiredVersion", previous == null ? 0 : previous.version()));
        return response(activated);
    }

    /**
     * Explicit bounded health probe. Never runs inside a DB transaction. P1: MANAGE-only side
     * effect applying the configured credential + server-owned UA + network/SSRF/redirect policy.
     * Unauthenticated GET against an auth-protected endpoint must not masquerade as AUTH failure
     * of the connection itself; the configured auth is always applied.
     */
    public ProbeResponse probe(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = require(context.organizationId(), id);
        var checkedAt = clock.instant();
        String status;
        String errorCode;
        try {
            endpointValidator.validateEndpoint(profile.baseUrl());
            var initial = ProviderTransportSupport.joinBase(profile.baseUrl(),
                    profile.modelsPath() == null ? "" : profile.modelsPath());
            endpointValidator.validateEndpoint(initial);
            var origin = java.net.URI.create(initial);
            // P1 fail-closed: a configured BEARER/API_KEY_HEADER connection without a usable
            // credential must FAIL here with zero outbound requests, never downgrade to an
            // anonymous request that could false-PASS against an open endpoint.
            var secret = resolveProbeSecretOrThrow(profile);
            var authenticated = secret != null;
            var userAgent = ProviderTransportSupport.serverUserAgent(profile.userAgent(),
                    profile.templateCode());
            var headers = probeHeaders(profile, userAgent, secret);
            var current = initial;
            int code = -1;
            for (var hop = 0; hop <= ProviderTransportSupport.MAX_REDIRECTS; hop++) {
                final ProviderControlPlaneTransport.Result httpResponse;
                try {
                    httpResponse = transport.get(current, headers, profile.connectTimeoutMs(),
                            profile.responseTimeoutMs());
                } catch (ProviderControlPlaneTransport.TransportException ex) {
                    throw mapProbeTransport(ex);
                }
                code = httpResponse.statusCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    var location = httpResponse.firstHeader("location");
                    if (location.isBlank() || hop == ProviderTransportSupport.MAX_REDIRECTS) {
                        throw new RedirectBlockedException();
                    }
                    final java.net.URI resolved;
                    try {
                        resolved = ProviderTransportSupport.resolveRedirect(current, location);
                    } catch (IllegalArgumentException ex) {
                        throw new RedirectBlockedException();
                    }
                    endpointValidator.validateRedirectTarget(resolved.toString());
                    if (authenticated && !ProviderTransportSupport.sameOrigin(origin, resolved)) {
                        throw new RedirectBlockedException();
                    }
                    current = resolved.toString();
                    continue;
                }
                break;
            }
            if (code >= 200 && code < 300) {
                status = "PASS";
                errorCode = null;
            } else if (code == 401 || code == 403) {
                status = "FAIL";
                errorCode = "AUTHENTICATION_FAILED";
            } else if (code == 404) {
                status = "FAIL";
                errorCode = "MODEL_NOT_FOUND";
            } else if (code == 429) {
                status = "FAIL";
                errorCode = "RATE_LIMITED";
            } else {
                status = "FAIL";
                errorCode = "PROVIDER_UNAVAILABLE";
            }
        } catch (ProbeAuthException ex) {
            status = "FAIL";
            errorCode = "AUTHENTICATION_FAILED";
        } catch (RedirectBlockedException ex) {
            status = "FAIL";
            errorCode = "ENDPOINT_BLOCKED";
        } catch (DomainException ex) {
            status = "FAIL";
            errorCode = "ENDPOINT_BLOCKED";
        } catch (ProbeTransportException ex) {
            status = "FAIL";
            errorCode = ex.errorCode;
        } catch (Exception ex) {
            status = "FAIL";
            errorCode = "PROVIDER_UNAVAILABLE";
        }
        audit.append("PROVIDER_CONNECTION_PROBED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", id, Map.of("result", status,
                        "errorCode", errorCode == null ? "NONE" : errorCode));
        return new ProbeResponse(status, errorCode, checkedAt);
    }

    private java.util.Map<String, String> probeHeaders(
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

    private ProbeTransportException mapProbeTransport(ProviderControlPlaneTransport.TransportException ex) {
        return switch (ex.errorCode()) {
            case ProviderControlPlaneTransport.TransportException.DNS_FAILED ->
                new ProbeTransportException("DNS_FAILED");
            case ProviderControlPlaneTransport.TransportException.TLS_FAILED ->
                new ProbeTransportException("TLS_FAILED");
            case ProviderControlPlaneTransport.TransportException.CONNECTION_TIMEOUT ->
                new ProbeTransportException("CONNECTION_TIMEOUT");
            default -> new ProbeTransportException("PROVIDER_UNAVAILABLE");
        };
    }

    /**
     * Fail-closed credential resolution: BEARER/API_KEY_HEADER without a usable secret throws
     * before any outbound request is emitted. NONE expects no credential.
     */
    private String resolveProbeSecretOrThrow(com.aicostops.providerhub.domain.ProviderConnection profile)
            throws ProbeAuthException {
        if ("NONE".equals(profile.authType())) {
            return null;
        }
        var credential = credentials.findActive(profile.organizationId(), profile.providerAccountId());
        if (credential == null || providerKek == null || providerKek.isBlank()) {
            throw new ProbeAuthException();
        }
        try {
            return new com.aicostops.gatewayadmin.security.ProviderCredentialEncryptor(providerKek).decrypt(
                    credential.ciphertext(), credential.nonce(), profile.organizationId(),
                    profile.providerAccountId(), credential.credentialType(),
                    credential.encryptionKeyVersion());
        } catch (Exception ex) {
            throw new ProbeAuthException();
        }
    }

    @Deprecated
    private String resolveProbeSecret(com.aicostops.providerhub.domain.ProviderConnection profile) {
        try {
            return resolveProbeSecretOrThrow(profile);
        } catch (ProbeAuthException ex) {
            return null;
        }
    }

    private static final class RedirectBlockedException extends Exception {
    }

    private static final class ProbeAuthException extends Exception {
    }

    private static final class ProbeTransportException extends Exception {
        private final String errorCode;

        private ProbeTransportException(String errorCode) {
            this.errorCode = errorCode;
        }
    }

    public List<ConnectionResponse> revisions(AuthenticatedUser user, long accountId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return mapper.listRevisions(context.organizationId(), accountId).stream().map(this::response).toList();
    }

    public List<ConnectionResponse> revisionsOf(AuthenticatedUser user, long connectionId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var current = require(context.organizationId(), connectionId);
        return revisions(user, current.providerAccountId());
    }

    /** Creates a new DRAFT revision copying the latest editable fields. */
    @Transactional
    public ConnectionResponse createRevision(AuthenticatedUser user, long connectionId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var current = requireForUpdate(context.organizationId(), connectionId);
        var revisions = mapper.listRevisions(context.organizationId(), current.providerAccountId());
        var latest = revisions.isEmpty() ? current : revisions.get(0);
        var version = mapper.nextVersion(context.organizationId(), current.providerAccountId());
        var now = clock.instant();
        mapper.insert(context.organizationId(), current.providerAccountId(), version,
                latest.connectionKind(), latest.templateCode(), latest.protocolCode(),
                latest.baseUrl(), latest.completionPath(), latest.modelsPath(),
                latest.authType(), latest.authHeaderName(), latest.networkPolicy(),
                latest.userAgent(), latest.connectTimeoutMs(), latest.responseTimeoutMs(),
                "DRAFT", context.organizationMemberId(), now, null);
        var created = require(context.organizationId(), mapper.lastInsertId());
        audit.append("PROVIDER_CONNECTION_REVISION_CREATED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", created.id(),
                Map.of("accountId", created.providerAccountId(), "version", created.version(),
                        "status", created.status()));
        return response(created);
    }

    private ProviderConnection require(long organizationId, long id) {
        var row = mapper.find(id, organizationId);
        if (row == null) {
            throw notFound("Provider connection was not found.");
        }
        return row;
    }

    private ProviderConnection requireForUpdate(long organizationId, long id) {
        var row = mapper.findForUpdate(id, organizationId);
        if (row == null) {
            throw notFound("Provider connection was not found.");
        }
        return row;
    }

    private void rejectBuiltinOverride(CreateConnectionRequest request) {
        if (request.baseUrl() != null || request.authType() != null
                || request.authHeaderName() != null || request.completionPath() != null
                || request.modelsPath() != null || request.userAgent() != null) {
            throw validationFailed("Built-in template fields are server-owned.");
        }
    }

    private ConnectionResponse response(ProviderConnection row) {
        return new ConnectionResponse(row.id(), row.providerAccountId(), row.version(),
                row.connectionKind(), row.templateCode(), row.protocolCode(), row.baseUrl(),
                row.completionPath(), row.modelsPath(), row.authType(), row.networkPolicy(),
                row.connectTimeoutMs(), row.responseTimeoutMs(), row.status(),
                row.createdAt(), row.activatedAt(), row.retiredAt());
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validationFailed(message);
        }
        return value.strip();
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Provider connection validation failed", detail);
    }

    private DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Provider connection not found", detail);
    }

    private DomainException stateConflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Provider connection state conflict", detail);
    }
}
