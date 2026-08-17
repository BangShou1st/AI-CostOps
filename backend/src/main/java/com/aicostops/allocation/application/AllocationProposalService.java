package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationReadModels.AllocationChargeRow;
import com.aicostops.allocation.application.AllocationReadModels.AllocationDecisionView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationProposalView;
import com.aicostops.allocation.application.AllocationReadModels.AllocationRuleTrace;
import com.aicostops.allocation.application.AllocationReadModels.NoMatchReason;
import com.aicostops.allocation.application.AllocationReadModels.ProposalStatus;
import com.aicostops.allocation.application.RuleEvaluator.Evaluation;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper.HintRow;
import com.aicostops.attribution.application.AllocationDecisionRepository;
import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.application.AllocationTargetDirectory;
import com.aicostops.attribution.application.NewAllocationDecisionDraft;
import com.aicostops.attribution.application.NewAllocationLine;
import com.aicostops.attribution.domain.AllocationDecision;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic rule proposal for one charge.
 *
 * <p>The evaluator consumes only the canonical charge, its confirmed-import
 * lineage, and its canonical attribution hint; it produces at most one RULE
 * DRAFT with a single full-amount line pointing at the winning rule's target.
 * A proposal never confirms, never posts, and never touches the current
 * decision pointer. Manual drafts and confirmed allocations suppress rules;
 * a changed winning rule supersedes the previous RULE draft while preserving
 * its lines.
 */
@Service
public class AllocationProposalService {

    private static final String PERMISSION_ALLOCATION_EDIT = "ALLOCATION_EDIT";
    private static final int DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationDecisionRepository decisions;
    private final AllocationRuleRepository rules;
    private final AllocationTargetDirectory targets;
    private final AllocationChargeFactMapper charges;
    private final AllocationIdempotency idempotency;
    private final AllocationResponseCodec codec;
    private final TransactionTemplate transactions;

    public AllocationProposalService(
            AuthorizationContextService authorizationContexts,
            AllocationDecisionRepository decisions,
            AllocationRuleRepository rules,
            AllocationTargetDirectory targets,
            AllocationChargeFactMapper charges,
            AllocationIdempotency idempotency,
            AllocationResponseCodec codec,
            PlatformTransactionManager transactionManager) {
        this.authorizationContexts = authorizationContexts;
        this.decisions = decisions;
        this.rules = rules;
        this.targets = targets;
        this.charges = charges;
        this.idempotency = idempotency;
        this.codec = codec;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AllocationProposalView propose(AuthenticatedUser user, long chargeFactId,
            String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_EDIT);
        AllocationIdempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.proposalRequestHash(context.organizationId(),
                context.organizationMemberId(), chargeFactId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var reserved = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), AllocationIdempotency.OPERATION_PROPOSAL,
                    idempotencyKey, requestHash);
            if (reserved.replay()) {
                return codec.proposalFromJson(reserved.responseBody());
            }
            var charge = Optional.ofNullable(charges.selectChargeForUpdate(
                            context.organizationId(), chargeFactId))
                    .orElseThrow(this::chargeNotFound);
            requireEligible(charge);
            var lineage = charges.selectLineage(context.organizationId(), chargeFactId);
            if (lineage == null || !lineage.confirmedImport()) {
                throw notEligible(
                        "The charge does not belong to the confirmed import lineage.");
            }
            var hint = charges.selectHint(context.organizationId(),
                    charge.rawRecordId(), charge.factIndex());
            var drafts = decisions.findDraftDecisionsByChargeForUpdate(
                    context.organizationId(), chargeFactId);
            if (drafts.stream().anyMatch(draft ->
                    draft.decisionSource() == AllocationDecisionSource.MANUAL)) {
                throw manualDraftExists();
            }
            if (decisions.countConfirmedForCharge(context.organizationId(), chargeFactId) > 0) {
                throw alreadyConfirmed();
            }
            var evaluation = evaluate(charge, hint, lineage.providerAccountId());
            if (!evaluation.matched()) {
                var noMatch = new AllocationProposalView(
                        ProposalStatus.NO_MATCH, null, null, evaluation.noMatchReason());
                idempotency.finalize(reserved.id(), 200, codec.proposalToJson(noMatch));
                return noMatch;
            }
            var winningRule = evaluation.winningRule();
            if (!targetActive(context.organizationId(), winningRule)) {
                // The rule is intact but its target died: it can no longer win.
                var noMatch = new AllocationProposalView(
                        ProposalStatus.NO_MATCH, null, null, NoMatchReason.NO_RULE_MATCH);
                idempotency.finalize(reserved.id(), 200, codec.proposalToJson(noMatch));
                return noMatch;
            }
            var existing = drafts.stream()
                    .filter(draft -> draft.decisionSource() == AllocationDecisionSource.RULE)
                    .filter(draft -> winningRule.id() == draft.allocationRuleId())
                    .findFirst();
            if (existing.isPresent()) {
                var view = buildView(context.organizationId(), existing.get());
                var reused = new AllocationProposalView(
                        ProposalStatus.REUSED, view, traceOf(view), null);
                idempotency.finalize(reserved.id(), 200, codec.proposalToJson(reused));
                return reused;
            }
            for (var draft : drafts) {
                // The winning rule changed: supersede the old RULE drafts,
                // preserving their lines, and create the new proposal draft.
                decisions.supersedeDecision(context.organizationId(), draft.id());
            }
            var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                    context.organizationId(), AllocationSubjectType.CHARGE_FACT, chargeFactId, null,
                    AllocationDecisionSource.RULE, winningRule.id(), null));
            decisions.insertLine(new NewAllocationLine(
                    context.organizationId(), decisionId, 0,
                    charge.amount(), charge.currency(),
                    winningRule.targetProjectId(), winningRule.targetCostCenterId(),
                    winningRule.targetTeamId()));
            var createdDecision = decisions.findByIdAndOrganization(
                            context.organizationId(), decisionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "A just-written proposal decision must be readable"));
            var view = buildView(context.organizationId(), createdDecision);
            var created = new AllocationProposalView(
                    ProposalStatus.CREATED, view, traceOf(view), null);
            idempotency.finalize(reserved.id(), 200, codec.proposalToJson(created));
            return created;
        }));
    }

    private Evaluation evaluate(AllocationChargeRow charge, HintRow hint, Long providerAccountId) {
        if (charge.periodStart() == null) {
            return Evaluation.noMatch(NoMatchReason.NO_EFFECTIVE_TIME);
        }
        if (hint == null) {
            return Evaluation.noMatch(NoMatchReason.NO_RULE_MATCH);
        }
        var matchHintType = parseMatchType(hint.hintType());
        if (matchHintType == null) {
            return Evaluation.noMatch(NoMatchReason.NO_RULE_MATCH);
        }
        var candidates = rules.findActiveMatching(charge.organizationId(),
                charge.providerCode(), providerAccountId, matchHintType,
                hint.providerValue(), charge.periodStart());
        return RuleEvaluator.evaluate(charge, hint, candidates);
    }

    private static com.aicostops.attribution.domain.AllocationRuleMatchType parseMatchType(
            String hintType) {
        try {
            return com.aicostops.attribution.domain.AllocationRuleMatchType.valueOf(hintType);
        } catch (IllegalArgumentException illegal) {
            return null;
        }
    }

    private boolean targetActive(long organizationId,
            com.aicostops.attribution.domain.AllocationRule rule) {
        if (rule.targetProjectId() != null) {
            return targets.activeProjectExists(organizationId, rule.targetProjectId());
        }
        if (rule.targetCostCenterId() != null) {
            return targets.activeCostCenterExists(organizationId, rule.targetCostCenterId());
        }
        return targets.activeTeamExists(organizationId, rule.targetTeamId());
    }

    private void requireEligible(AllocationChargeRow charge) {
        if (charge.reviewStatus() == ReviewStatus.EXCLUDED_DUPLICATE
                || charge.reviewStatus() == ReviewStatus.EXCLUDED_NONCOST) {
            throw notEligible("Excluded charges are not eligible for allocation proposals.");
        }
        if (charge.currentAllocationDecisionId() != null) {
            throw alreadyConfirmed();
        }
    }

    private AllocationDecisionView buildView(long organizationId, AllocationDecision decision) {
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

    private static AllocationRuleTrace traceOf(AllocationDecisionView view) {
        return view.ruleTrace();
    }

    private DomainException chargeNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Charge not found",
                "The charge is not available in the current organization.");
    }

    private static DomainException manualDraftExists() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.MANUAL_ALLOCATION_DRAFT_EXISTS,
                "Manual allocation draft exists",
                "The charge already has a MANUAL DRAFT allocation; rule proposals "
                        + "must not override it.");
    }

    private static DomainException alreadyConfirmed() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_ALREADY_CONFIRMED,
                "Allocation already confirmed",
                "The charge already has a confirmed allocation that cannot be rewritten.");
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Charge not eligible for allocation",
                detail);
    }

    private <T> T executeWithDeadlockRetry(java.util.function.Supplier<T> operation) {
        for (var attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DeadlockLoserDataAccessException deadlock) {
                if (attempt >= DEADLOCK_RETRIES) {
                    throw deadlock;
                }
            }
        }
    }
}
