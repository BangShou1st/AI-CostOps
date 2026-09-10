package com.aicostops.gatewayadmin.application;

import com.aicostops.gatewayadmin.api.ControlPlaneDtos;
import com.aicostops.gatewayadmin.infrastructure.ControlPlaneMapper;
import com.aicostops.gatewayadmin.security.GatewayKeyCodec;
import com.aicostops.gatewayadmin.security.GatewayKeyGenerator;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed Gateway Credential lifecycle: list (metadata only), detail
 * (metadata only), create (returns the raw key exactly once), revoke. The raw
 * key is never persisted: only the HMAC digest and the non-secret prefix are
 * stored, following the frozen AIC-092 contract. A revoked credential cannot
 * be reused (Gateway runtime rejects non-ACTIVE credentials), while incurred
 * work remains preserved (D03 semantics).
 */
@Service
public class GatewayCredentialService {

    private static final java.util.Set<String> BUDGET_MODES = java.util.Set.of("REQUIRED", "OPTIONAL");
    private static final java.util.Set<String> FINANCIAL_SCOPES = java.util.Set.of("PROJECT", "TEAM", "COST_CENTER");

    private final AuthorizationContextService authorizationContexts;
    private final ControlPlaneMapper mapper;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final GatewayAdminAuditPort audit;
    private final String credentialHmacKey;

    public GatewayCredentialService(AuthorizationContextService authorizationContexts,
            ControlPlaneMapper mapper, GatewayAdminAuditPort audit,
            @Value("${aicostops.gateway.credential-hmac-key-v1:}") String credentialHmacKey) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.audit = audit;
        this.credentialHmacKey = credentialHmacKey;
    }

    public List<ControlPlaneDtos.CredentialResponse> list(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return mapper.selectCredentials(context.organizationId()).stream().map(this::toResponse).toList();
    }

    public ControlPlaneDtos.CredentialResponse get(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return toResponse(requireCredential(context.organizationId(), id));
    }

    @Transactional
    public ControlPlaneDtos.CredentialCreateResponse create(AuthenticatedUser user,
            ControlPlaneDtos.CredentialCreateRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        long orgId = context.organizationId();

        String principalType = request.principalType().toUpperCase(Locale.ROOT);
        if (!principalType.equals("SERVICE") && !principalType.equals("HUMAN_MEMBER")) {
            throw validation("Principal type must be SERVICE or HUMAN_MEMBER.");
        }
        Long serviceIdentityId = principalType.equals("SERVICE") ? request.serviceIdentityId() : null;
        Long memberId = principalType.equals("HUMAN_MEMBER") ? request.organizationMemberId() : null;
        if (principalType.equals("SERVICE") && serviceIdentityId == null) {
            throw validation("A SERVICE credential requires serviceIdentityId.");
        }
        if (principalType.equals("HUMAN_MEMBER") && memberId == null) {
            throw validation("A HUMAN_MEMBER credential requires organizationMemberId.");
        }
        if (principalType.equals("SERVICE") && !"ACTIVE".equals(mapper.selectServiceIdentityStatus(orgId, serviceIdentityId))) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Service identity not found", "The service identity is not available in this organization.");
        }
        if (principalType.equals("HUMAN_MEMBER") && !mapper.memberExists(orgId, memberId)) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Organization member not found", "The organization member is not available in this organization.");
        }
        if (!"ACTIVE".equals(mapper.selectProjectStatus(orgId, request.projectId()))) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Project not found", "The project is not active in this organization.");
        }
        String financialScopeType = request.financialScopeType().toUpperCase(Locale.ROOT);
        if (!FINANCIAL_SCOPES.contains(financialScopeType)) {
            throw validation("Financial scope type must be PROJECT, TEAM or COST_CENTER.");
        }
        ensureFinancialScopeActive(orgId, financialScopeType, request.financialScopeId());
        String budgetMode = request.budgetEnforcementMode().toUpperCase(Locale.ROOT);
        if (!BUDGET_MODES.contains(budgetMode)) {
            throw validation("Budget enforcement mode must be REQUIRED or OPTIONAL.");
        }
        for (Long modelId : request.modelIds()) {
            if (!"ACTIVE".equals(mapper.selectModelStatus(modelId))) {
                throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                        "Model not available", "Every credential model must be ACTIVE in the global catalog.");
            }
        }

        String rawKey = GatewayKeyGenerator.generateRawKey();
        var parsed = GatewayKeyCodec.parse(rawKey);
        byte[] digest = GatewayKeyCodec.digestSecret(parsed.secretPart(), credentialHmacKey);
        mapper.insertCredential(orgId, parsed.prefix(), digest, principalType, memberId,
                serviceIdentityId, request.projectId(), financialScopeType, request.financialScopeId(),
                budgetMode, request.expiresAt());
        long id = mapper.lastInsertId();
        for (Long modelId : request.modelIds()) {
            mapper.insertCredentialModel(id, orgId, modelId);
        }
        audit.gatewayCredentialCreated(orgId, user.userId(), id, parsed.prefix(), principalType, "ACTIVE");
        var row = requireCredential(orgId, id);
        return new ControlPlaneDtos.CredentialCreateResponse(
                ApiId.of(id), parsed.prefix(), rawKey, row.principalType(),
                ApiId.of(row.projectId()), row.financialScopeType(), ApiId.of(row.financialScopeId()),
                row.budgetEnforcementMode(), row.status(), row.expiresAt(), row.createdAt());
    }

    @Transactional
    public ControlPlaneDtos.CredentialResponse revoke(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        long orgId = context.organizationId();
        var row = requireCredential(orgId, id);
        if (!row.status().equals("ACTIVE")) {
            // Deterministic repeat-revoke behavior.
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Gateway credential is not active",
                    "Only an ACTIVE credential can be revoked; this one is " + row.status() + ".");
        }
        mapper.revokeCredential(orgId, id);
        audit.gatewayCredentialRevoked(orgId, user.userId(), id, row.prefix(), row.status());
        return toResponse(requireCredential(orgId, id));
    }

    private void ensureFinancialScopeActive(long orgId, String scopeType, long scopeId) {
        String status;
        if (scopeType.equals("PROJECT")) {
            status = mapper.selectProjectStatus(orgId, scopeId);
        } else if (scopeType.equals("TEAM")) {
            status = mapper.selectTeamStatus(orgId, scopeId);
        } else {
            status = mapper.selectCostCenterStatus(orgId, scopeId);
        }
        if (!"ACTIVE".equals(status)) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Financial scope not found", "The financial scope is not active in this organization.");
        }
    }

    private ControlPlaneMapper.CredentialRow requireCredential(long orgId, long id) {
        var row = mapper.selectCredential(orgId, id);
        if (row == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Resource not found", "The resource is not available at the granted scope.");
        }
        return row;
    }

    private ControlPlaneDtos.CredentialResponse toResponse(ControlPlaneMapper.CredentialRow row) {
        return new ControlPlaneDtos.CredentialResponse(
                ApiId.of(row.id()), row.prefix(), row.principalType(),
                row.serviceIdentityId() == null ? null : ApiId.of(row.serviceIdentityId()),
                row.organizationMemberId() == null ? null : ApiId.of(row.organizationMemberId()),
                ApiId.of(row.projectId()), row.financialScopeType(), ApiId.of(row.financialScopeId()),
                row.budgetEnforcementMode(), row.status(), row.expiresAt(), row.createdAt(),
                row.revokedAt());
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Gateway credential validation failed", detail);
    }
}