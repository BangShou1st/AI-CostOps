package com.aicostops.budget.application;

import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.budget.infrastructure.BudgetMapper.ScopeConstraint;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Budget read model: backend-computed {@code available} / {@code overBudget}
 * (the client never recomputes business truth), organization-scoped lookups,
 * and grant-scoped visibility — a caller with only typed {@code BUDGET_READ}
 * grants sees only budgets whose own scope matches one of those grants; a
 * wrong organization or an invisible budget is a privacy-preserving 404.
 */
@Service
public class BudgetQueryService {

    private static final String PERMISSION_BUDGET_READ = "BUDGET_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BudgetMapper mapper;

    public BudgetQueryService(
            AuthorizationContextService authorizationContexts,
            BudgetMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public PageResponse<Budget> list(AuthenticatedUser user, PageRequest page,
            Long billingPeriodId, ScopeType scopeType, Long scopeId) {
        var context = authorizationContexts.current(user);
        requireReadGrant(context);
        if ((scopeType == null) != (scopeId == null)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid budget filter",
                    "scopeType and scopeId must be provided together.");
        }
        var visibility = visibility(context);
        if (!visibility.organizationWide() && visibility.visibleScopes().isEmpty()) {
            return PageResponse.of(List.of(), page, 0);
        }
        var scopeTypeName = scopeType == null ? null : scopeType.name();
        var total = mapper.count(context.organizationId(), billingPeriodId, scopeTypeName,
                scopeId, visibility.organizationWide(), visibility.visibleScopes());
        var budgets = mapper.selectPage(context.organizationId(), billingPeriodId, scopeTypeName,
                scopeId, visibility.organizationWide(), visibility.visibleScopes(),
                page.size(), Math.multiplyExact(page.page(), page.size()));
        return PageResponse.of(budgets, page, total);
    }

    public Budget get(AuthenticatedUser user, long budgetId) {
        var context = authorizationContexts.current(user);
        requireReadGrant(context);
        var budget = mapper.selectByIdAndOrganization(context.organizationId(), budgetId);
        if (budget == null) {
            throw notFound();
        }
        authorization.requireResource(context, PERMISSION_BUDGET_READ,
                budget.scopeType(), budget.scopeId());
        return budget;
    }

    private void requireReadGrant(com.aicostops.iam.domain.AuthorizationContext context) {
        var hasReadGrant = context.grants().stream().anyMatch(grant ->
                grant.permissionCode().equals(PERMISSION_BUDGET_READ)
                        && M1AdminPermissionPolicy.applicableScopes(PERMISSION_BUDGET_READ)
                                .contains(grant.scopeType()));
        if (!hasReadGrant) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Permission is required",
                    "The required permission is not granted at an applicable scope.");
        }
    }

    /**
     * An ORG-scope grant of the current organization sees every budget.
     * Otherwise only budgets whose own scope matches one of the caller's
     * typed grants are visible; ORG-scope budgets stay invisible to typed-only
     * callers (no org-wide totals leak).
     */
    private Visibility visibility(com.aicostops.iam.domain.AuthorizationContext context) {
        var readGrants = context.grants().stream()
                .filter(grant -> grant.permissionCode().equals(PERMISSION_BUDGET_READ))
                .filter(grant -> M1AdminPermissionPolicy.applicableScopes(PERMISSION_BUDGET_READ)
                        .contains(grant.scopeType()))
                .toList();
        if (readGrants.stream().anyMatch(grant -> grant.scopeType() == ScopeType.ORG
                && grant.scopeId() == context.organizationId())) {
            return new Visibility(true, List.of());
        }
        var visibleScopes = readGrants.stream()
                .filter(grant -> grant.scopeType() != ScopeType.ORG)
                .map(grant -> new ScopeConstraint(grant.scopeType().name(), grant.scopeId()))
                .distinct()
                .toList();
        return new Visibility(false, visibleScopes);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Budget not found", "The budget is not available in the current organization.");
    }

    private record Visibility(boolean organizationWide, List<ScopeConstraint> visibleScopes) {
    }
}