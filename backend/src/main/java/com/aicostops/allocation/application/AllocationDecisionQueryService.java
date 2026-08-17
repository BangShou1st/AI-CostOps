package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper;
import com.aicostops.attribution.application.AllocationDecisionRepository;
import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.expense.application.ExpenseAllocationSourcePort;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Allocation decision reads. Every call requires ALLOCATION_READ at ORG scope;
 * decisions of other organizations are invisible (privacy-preserving 404).
 */
@Service
public class AllocationDecisionQueryService {

    private static final String PERMISSION_ALLOCATION_READ = "ALLOCATION_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationDecisionRepository decisions;
    private final AllocationRuleRepository rules;
    private final AllocationChargeFactMapper charges;
    private final ExpenseAllocationSourcePort expenseSource;

    public AllocationDecisionQueryService(
            AuthorizationContextService authorizationContexts,
            AllocationDecisionRepository decisions,
            AllocationRuleRepository rules,
            AllocationChargeFactMapper charges,
            ExpenseAllocationSourcePort expenseSource) {
        this.authorizationContexts = authorizationContexts;
        this.decisions = decisions;
        this.rules = rules;
        this.charges = charges;
        this.expenseSource = expenseSource;
    }

    /** Every decision of one charge (the charge must be visible). */
    public List<AllocationDecisionView> listByCharge(AuthenticatedUser user, long chargeFactId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_READ);
        if (charges.selectCharge(context.organizationId(), chargeFactId) == null) {
            throw notFound();
        }
        return decisions.findDecisionsByCharge(context.organizationId(), chargeFactId).stream()
                .map(decision -> viewOf(context.organizationId(), decision))
                .toList();
    }

    /** Every decision of one expense (the expense must be visible). */
    public List<AllocationDecisionView> listByExpense(AuthenticatedUser user, long expenseClaimId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_READ);
        if (!expenseSource.exists(context.organizationId(), expenseClaimId)) {
            throw notFound();
        }
        return decisions.findDecisionsByExpense(context.organizationId(), expenseClaimId).stream()
                .map(decision -> viewOf(context.organizationId(), decision))
                .toList();
    }

    public AllocationDecisionView get(AuthenticatedUser user, long decisionId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_READ);
        return decisions.findByIdAndOrganization(context.organizationId(), decisionId)
                .map(decision -> viewOf(context.organizationId(), decision))
                .orElseThrow(this::notFound);
    }

    private AllocationDecisionView viewOf(long organizationId, AllocationDecision decision) {
        var lines = decisions.linesOfDecision(organizationId, decision.id());
        AllocationRuleTrace trace = null;
        if (decision.allocationRuleId() != null) {
            trace = rules.findByIdAndOrganization(organizationId, decision.allocationRuleId())
                    .map(rule -> new AllocationRuleTrace(
                            rule.id(), rule.ruleKey(), rule.version(), rule.priority()))
                    .orElse(null);
        }
        return new AllocationDecisionView(decision, lines, trace);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Allocation decision not found",
                "The allocation decision is not available in the current organization.");
    }
}
