package com.aicostops.advisor.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.aicostops.advisor.application.AdvisorErrors.*;
import com.aicostops.advisor.infrastructure.AdvisorProfileMapper;
import com.aicostops.advisor.infrastructure.AdvisorJobMapper;
import com.aicostops.advisor.infrastructure.AdvisorRows.ProfileRow;
import com.aicostops.advisor.application.AdvisorReadModels.ProfileResponse;
import com.aicostops.advisor.application.AdvisorRequests.UpdateProfileRequest;

/** 组织配置的授权、版本更新与审计；凭据轮换及旧任务失效共用本入口事务。 */
@Service
public class AdvisorProfileService {
    private final AuthorizationContextService authorizationContexts;
    private final AdvisorProfileMapper mapper;
    private final AdvisorJobMapper jobs;
    private final AdvisorCredentialRotator credentials;
    private final AuditService audit;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public AdvisorProfileService(
            AuthorizationContextService authorizationContexts,
            AdvisorProfileMapper mapper,
            AdvisorJobMapper jobs,
            AdvisorCredentialRotator credentials,
            AuditService audit,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.jobs = jobs;
        this.credentials = credentials;
        this.audit = audit;
        this.clock = clock;
    }

    public ProfileResponse profile(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_USE");
        var row = mapper.findActiveProfile(context.organizationId());
        if (row == null) {
            throw notFound("Advisor profile is not configured.");
        }
        return profileResponse(row);
    }

    @Transactional
    public ProfileResponse updateProfile(AuthenticatedUser user, UpdateProfileRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "AI_ADVISOR_MANAGE");
        // P1: org-visible provider model (global OR same-org private, ACTIVE). Never cross-org private.
        if (!"ACTIVE".equals(mapper.findOrgVisibleProviderModelStatus(request.providerModelId(),
                context.organizationId()))) {
            throw validationFailed("Advisor provider model must be ACTIVE and visible to this organization.");
        }
        if (!"ACTIVE".equals(mapper.findProjectStatus(request.projectId(), context.organizationId()))) {
            throw validationFailed("Advisor project must be ACTIVE in this organization.");
        }
        var scope = request.financialScopeType() == null ? "" : request.financialScopeType();
        if (!scope.equals("PROJECT") && !scope.equals("TEAM") && !scope.equals("COST_CENTER")) {
            throw validationFailed("Financial scope type must be PROJECT, TEAM or COST_CENTER.");
        }
        // P1: financialScopeId must belong to current org with matching type and ACTIVE status.
        validateFinancialScopeOwnership(context.organizationId(), scope, request.financialScopeId());
        var budgetMode = request.budgetEnforcementMode() == null ? "" : request.budgetEnforcementMode();
        if (!budgetMode.equals("REQUIRED") && !budgetMode.equals("OPTIONAL")) {
            throw validationFailed("Budget enforcement mode must be REQUIRED or OPTIONAL.");
        }
        var now = clock.instant();
        var previous = mapper.findActiveProfile(context.organizationId());
        if (previous != null) {
            mapper.retireProfile(previous.id(), context.organizationId(), now);
        }
        var version = mapper.nextProfileVersion(context.organizationId());
        mapper.insertProfile(context.organizationId(), version, request.providerModelId(),
                request.projectId(), scope, request.financialScopeId(), budgetMode,
                context.organizationMemberId(), now);
        var created = mapper.findProfile(mapper.lastInsertId(), context.organizationId());
        // P0: rotate INTERNAL_SYSTEM credential so execution principal exactly matches new ACTIVE profile.
        credentials.rotate(context.organizationId(), created.id(), request.projectId(), scope,
                request.financialScopeId(), budgetMode, request.providerModelId(), now);
        // P0 frozen-job principle: undispatched work bound to the superseded revision (or legacy
        // unbound rows) deterministically fails here so it can never silently execute under v2.
        // Already-linked executions keep converging on their own Gateway lineage instead.
        if (previous != null) {
            var superseded = jobs.supersedePendingJobs(context.organizationId(), previous.id(), now);
            // P1-1 atomic terminal convergence: the current PENDING child attempt of every
            // superseded job must fail in the same DB transaction (never job FAILED + attempt PENDING).
            // Provider I/O never occurs here. Both updates share this @Transactional boundary,
            // so any attempt-update failure rolls back the job update as well.
            var supersededAttempts = jobs.supersedePendingAttempts(context.organizationId(), previous.id());
            if (superseded != supersededAttempts) {
                throw new IllegalStateException("Advisor supersede left job/attempt split state");
            }
            audit.append("AI_ADVISOR_PROFILE_UPDATED", context.organizationId(), user.userId(),
                    "ADVISOR_PROFILE", created.id(),
                    Map.of("version", created.version(), "supersededJobs", superseded));
        } else {
            audit.append("AI_ADVISOR_PROFILE_UPDATED", context.organizationId(), user.userId(),
                    "ADVISOR_PROFILE", created.id(), Map.of("version", created.version()));
        }
        return profileResponse(created);
    }

    private void validateFinancialScopeOwnership(long organizationId, String scopeType, long scopeId) {
        final String status = switch (scopeType) {
            case "PROJECT" -> mapper.findProjectStatus(scopeId, organizationId);
            case "TEAM" -> mapper.findTeamStatus(scopeId, organizationId);
            case "COST_CENTER" -> mapper.findCostCenterStatus(scopeId, organizationId);
            default -> null;
        };
        if (!"ACTIVE".equals(status)) {
            throw validationFailed("Advisor financial scope must be ACTIVE in this organization.");
        }
    }

    private ProfileResponse profileResponse(ProfileRow row) {
        return new ProfileResponse(row.id(), row.version(), row.providerModelId(), row.projectId(),
                row.financialScopeType(), row.financialScopeId(), row.budgetEnforcementMode(),
                row.status());
    }
}
