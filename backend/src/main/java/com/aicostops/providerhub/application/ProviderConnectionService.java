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
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
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
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ProviderConnectionService(
            AuthorizationContextService authorizationContexts,
            ProviderConnectionMapper mapper,
            ProviderTemplateRegistry templates,
            CustomEndpointValidator endpointValidator,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.templates = templates;
        this.endpointValidator = endpointValidator;
        this.audit = audit;
        this.clock = clock;
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

    @Transactional
    public ConnectionResponse create(AuthenticatedUser user, CreateConnectionRequest request) {
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
            endpointValidator.validateEndpoint(baseUrl);
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

    @Transactional
    public ConnectionResponse updateDraft(AuthenticatedUser user, long id, UpdateConnectionRequest request) {
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
        endpointValidator.validateAuthHeaderName(authType, request.authHeaderName());
        if (!baseUrl.equals(current.baseUrl())) {
            endpointValidator.validateEndpoint(baseUrl);
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

    @Transactional
    public ConnectionResponse activate(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var target = requireForUpdate(context.organizationId(), id);
        if (!"DRAFT".equals(target.status())) {
            throw stateConflict("Only DRAFT connections can be activated.");
        }
        endpointValidator.validateEndpoint(target.baseUrl());
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

    /** Explicit bounded health probe. Never runs inside a DB transaction. */
    public ProbeResponse probe(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var profile = require(context.organizationId(), id);
        var checkedAt = clock.instant();
        String status;
        String errorCode;
        try {
            endpointValidator.validateEndpoint(profile.baseUrl());
            var target = profile.baseUrl() + (profile.modelsPath() == null ? "" : profile.modelsPath());
            var client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(null))
                    .connectTimeout(Duration.ofMillis(profile.connectTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            var httpRequest = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMillis(profile.responseTimeoutMs()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            var code = httpResponse.statusCode();
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
        } catch (DomainException ex) {
            status = "FAIL";
            errorCode = "ENDPOINT_BLOCKED";
        } catch (SSLHandshakeException ex) {
            status = "FAIL";
            errorCode = "TLS_FAILED";
        } catch (java.net.ConnectException | java.net.UnknownHostException ex) {
            status = "FAIL";
            errorCode = "DNS_FAILED";
        } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException ex) {
            status = "FAIL";
            errorCode = "CONNECTION_TIMEOUT";
        } catch (Exception ex) {
            status = "FAIL";
            errorCode = "PROVIDER_UNAVAILABLE";
        }
        audit.append("PROVIDER_CONNECTION_PROBED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", id, Map.of("result", status,
                        "errorCode", errorCode == null ? "NONE" : errorCode));
        return new ProbeResponse(status, errorCode, checkedAt);
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
