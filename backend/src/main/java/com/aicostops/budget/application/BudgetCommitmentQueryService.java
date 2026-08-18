package com.aicostops.budget.application;

import com.aicostops.budget.application.CommitmentReadModels.CommitmentDetail;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.budget.infrastructure.BudgetMapper.ScopeConstraint;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
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
 * Commitment read model: organization-scoped lookups and grant-scoped
 * visibility like the budget read model. A caller with only typed BUDGET_READ
 * grants sees only commitments whose budget's own scope matches one of those
 * grants; a wrong organization or an invisible commitment is a
 * privacy-preserving 404.
 */
@Service
public class BudgetCommitmentQueryService {

    private static final String PERMISSION_BUDGET_READ = "BUDGET_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BudgetMapper budgetMapper;
    private final BudgetCommitmentMapper commitmentMapper;

    public BudgetCommitmentQueryService(
            AuthorizationContextService authorizationContexts,
            BudgetMapper budgetMapper,
            BudgetCommitmentMapper commitmentMapper) {
        this.authorizationContexts = authorizationContexts;
        this.budgetMapper = budgetMapper;
        this.commitmentMapper = commitmentMapper;
    }

    public PageResponse<CommitmentDetail> list(AuthenticatedUser user, PageRequest page,
            Long budgetId, String status) {
        var context = authorizationContexts.current(user);
        requireReadGrant(context);
        if (budgetId != null) {
            var budget = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                    budgetId);
            if (budget == null) {
                throw notFound();
            }
            authorization.requireResource(context, PERMISSION_BUDGET_READ,
                    budget.scopeType(), budget.scopeId());
        }
        var visibility = visibility(context);
        if (!visibility.organizationWide() && visibility.visibleScopes().isEmpty()) {
            return PageResponse.of(List.of(), page, 0);
        }
        var total = commitmentMapper.count(context.organizationId(), budgetId, status,
                visibility.organizationWide(), visibility.visibleScopes());
        var commitments = commitmentMapper.selectPage(context.organizationId(), budgetId,
                status, visibility.organizationWide(), visibility.visibleScopes(),
                page.size(), Math.multiplyExact(page.page(), page.size()));
        return PageResponse.of(commitments.stream().map(this::toDetail).toList(), page, total);
    }

    public CommitmentDetail get(AuthenticatedUser user, long commitmentId) {
        var context = authorizationContexts.current(user);
        requireReadGrant(context);
        var commitment = commitmentMapper.selectByIdAndOrganization(
                context.organizationId(), commitmentId);
        if (commitment == null) {
            throw notFound();
        }
        var budget = budgetMapper.selectByIdAndOrganization(context.organizationId(),
                commitment.budgetId());
        if (budget == null) {
            throw notFound();
        }
        authorization.requireResource(context, PERMISSION_BUDGET_READ,
                budget.scopeType(), budget.scopeId());
        return toDetail(commitment);
    }

    private CommitmentDetail toDetail(BudgetCommitment commitment) {
        var approvalCase = commitmentMapper.selectApprovalCaseByCommitment(
                commitment.organizationId(), commitment.id());
        var history = approvalCase == null ? List.<com.aicostops.budget.domain.CommitmentApprovalAction>of()
                : commitmentMapper.selectApprovalActionsByCase(commitment.organizationId(),
                        approvalCase.id());
        return CommitmentDetail.from(commitment, approvalCase, history);
    }

    private void requireReadGrant(AuthorizationContext context) {
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

    private Visibility visibility(AuthorizationContext context) {
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
                "Commitment not found",
                "The commitment is not available in the current organization.");
    }

    private record Visibility(boolean organizationWide, List<ScopeConstraint> visibleScopes) {
    }
}
