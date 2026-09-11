package com.aicostops.providerhub.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.gatewayadmin.security.ProviderCredentialEncryptor;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.providerhub.infrastructure.ProviderConnectionMapper;
import com.aicostops.providerhub.infrastructure.ProviderCredentialAdminMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider secret lifecycle (M18 V3).
 *
 * <p>Secrets enter the existing AES-256-GCM encryption boundary and are
 * never returned, logged or audited. Rotation links the predecessor;
 * revocation preserves history. Safe projection carries labels only.
 */
@Service
public class ProviderCredentialService {

    private final AuthorizationContextService authorizationContexts;
    private final ProviderConnectionMapper connections;
    private final ProviderCredentialAdminMapper mapper;
    private final AuditService audit;
    private final Clock clock;
    private final String providerKek;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public ProviderCredentialService(
            AuthorizationContextService authorizationContexts,
            ProviderConnectionMapper connections,
            ProviderCredentialAdminMapper mapper,
            AuditService audit,
            Clock clock,
            @Value("${aicostops.gateway.provider-kek-v1:}") String providerKek) {
        this.authorizationContexts = authorizationContexts;
        this.connections = connections;
        this.mapper = mapper;
        this.audit = audit;
        this.clock = clock;
        this.providerKek = providerKek;
    }

    public List<CredentialResponse> list(AuthenticatedUser user, long connectionId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var profile = require(context.organizationId(), connectionId);
        return mapper.listSafe(context.organizationId(), profile.providerAccountId()).stream()
                .map(r -> new CredentialResponse(r.id(), r.credentialType(), r.safeLabel(),
                        r.status(), r.createdAt(), r.rotatedAt(), r.revokedAt()))
                .toList();
    }

    @Transactional
    public CredentialResponse create(AuthenticatedUser user, long connectionId, CredentialRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = require(context.organizationId(), connectionId);
        if ("NONE".equals(profile.authType())) {
            throw validationFailed("This connection uses no authentication.");
        }
        var credentialType = "BEARER".equals(profile.authType()) ? "BEARER_TOKEN" : "API_KEY";
        var secret = request.rawSecret() == null ? "" : request.rawSecret();
        if (secret.length() < 8 || secret.length() > 4096) {
            throw validationFailed("Provider secret has an illegal length.");
        }
        if (providerKek == null || providerKek.isBlank()) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                    "Credential service unavailable", "Provider key encryption is not configured.");
        }
        var encrypted = new ProviderCredentialEncryptor(providerKek).encrypt(secret,
                context.organizationId(), profile.providerAccountId(), credentialType, (short) 1);
        var now = clock.instant();
        var predecessor = mapper.findActiveId(context.organizationId(), profile.providerAccountId());
        mapper.insert(context.organizationId(), profile.providerAccountId(), credentialType,
                encrypted.ciphertext(), encrypted.nonce(),
                request.safeLabel() == null ? profile.authType() : request.safeLabel(),
                predecessor, now);
        var createdId = mapper.lastInsertId();
        audit.append("PROVIDER_CREDENTIAL_CREATED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", connectionId,
                Map.of("credentialType", credentialType, "accountId", profile.providerAccountId()));
        return new CredentialResponse(createdId, credentialType,
                request.safeLabel() == null ? profile.authType() : request.safeLabel(),
                "ACTIVE", now, null, null);
    }

    @Transactional
    public CredentialResponse rotate(AuthenticatedUser user, long connectionId, CredentialRequest request) {
        var created = create(user, connectionId, request);
        var context = authorizationContexts.current(user);
        var profile = require(context.organizationId(), connectionId);
        for (var row : mapper.listSafe(context.organizationId(), profile.providerAccountId())) {
            if (row.id() != created.id() && "ACTIVE".equals(row.status())) {
                mapper.revoke(row.id(), context.organizationId(), profile.providerAccountId(),
                        clock.instant());
            }
        }
        audit.append("PROVIDER_CREDENTIAL_ROTATED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", connectionId,
                Map.of("accountId", profile.providerAccountId()));
        return created;
    }

    @Transactional
    public void revoke(AuthenticatedUser user, long connectionId, long credentialId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var profile = require(context.organizationId(), connectionId);
        if (mapper.revoke(credentialId, context.organizationId(), profile.providerAccountId(),
                clock.instant()) != 1) {
            throw notFound("Provider credential was not found or is not ACTIVE.");
        }
        audit.append("PROVIDER_CREDENTIAL_REVOKED", context.organizationId(), user.userId(),
                "PROVIDER_CONNECTION", connectionId,
                Map.of("accountId", profile.providerAccountId()));
    }

    private com.aicostops.providerhub.domain.ProviderConnection require(long organizationId, long connectionId) {
        var profile = connections.find(connectionId, organizationId);
        if (profile == null) {
            throw notFound("Provider connection was not found.");
        }
        return profile;
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Provider credential validation failed", detail);
    }

    private DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Provider credential not found", detail);
    }

    public record CredentialRequest(String rawSecret, String safeLabel) {
    }

    public record CredentialResponse(
            long id, String credentialType, String safeLabel, String status,
            Instant createdAt, Instant rotatedAt, Instant revokedAt) {
    }
}
