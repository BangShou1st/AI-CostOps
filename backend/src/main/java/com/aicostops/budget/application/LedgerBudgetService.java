package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Implements deterministic budget selection and financial row locking. */
@Service
public class LedgerBudgetService implements LedgerBudgetPort {

    private final BillingPeriodMapper periodMapper;
    private final BudgetMapper budgetMapper;
    private final BudgetCommitmentMapper commitmentMapper;

    public LedgerBudgetService(BillingPeriodMapper periodMapper, BudgetMapper budgetMapper,
            BudgetCommitmentMapper commitmentMapper) {
        this.periodMapper = periodMapper;
        this.budgetMapper = budgetMapper;
        this.commitmentMapper = commitmentMapper;
    }

    @Override
    public BillingPeriod lockOpenPeriodAt(long organizationId, Instant effectiveAt) {
        var candidates = periodMapper.selectCoveringCandidates(organizationId, effectiveAt);
        if (candidates.size() != 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    candidates.isEmpty() ? "No covering billing period" : "Ambiguous billing periods",
                    candidates.isEmpty()
                            ? "No billing period covers the posting effective time."
                            : "More than one billing period covers the posting effective time.");
        }
        var candidate = candidates.get(0);
        var locked = periodMapper.selectByIdForUpdate(organizationId, candidate.id());
        if (locked == null || !locked.covers(effectiveAt)) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Billing period changed", "The covering billing period changed while posting.");
        }
        if (locked.status() != BillingPeriodStatus.OPEN) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                    "Billing period is not open",
                    "The posting billing period is " + locked.status() + ".");
        }
        return locked;
    }

    @Override
    public BillingPeriod lockOpenPeriod(long organizationId, long billingPeriodId) {
        var locked = periodMapper.selectByIdForUpdate(organizationId, billingPeriodId);
        if (locked == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Billing period not found",
                    "The billing period is not available in the current organization.");
        }
        if (locked.status() != BillingPeriodStatus.OPEN) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                    "Billing period is not open",
                    "The correction billing period is " + locked.status() + ".");
        }
        return locked;
    }

    @Override
    public List<BudgetSelection> resolveSelections(long organizationId, long billingPeriodId,
            List<EntryScopeAmount> entries) {
        return entries.stream().map(entry -> {
            var exact = budgetMapper.selectByIdentity(organizationId, billingPeriodId,
                    entry.scopeType().name(), entry.scopeId(), entry.currency());
            var selected = exact != null ? exact : budgetMapper.selectByIdentity(
                    organizationId, billingPeriodId, "ORG", organizationId, entry.currency());
            return new BudgetSelection(entry.entryIndex(), selected);
        }).toList();
    }

    @Override
    public List<Budget> lockBudgets(long organizationId, Collection<Long> budgetIds) {
        return budgetIds.stream().filter(Objects::nonNull).distinct().sorted()
                .map(id -> {
                    var budget = budgetMapper.selectByIdForUpdate(organizationId, id);
                    if (budget == null) {
                        throw notFound("Budget", id);
                    }
                    return budget;
                }).toList();
    }

    @Override
    public List<BudgetCommitment> lockCommitments(long organizationId,
            Collection<Long> commitmentIds) {
        return commitmentIds.stream().filter(Objects::nonNull).distinct().sorted()
                .map(id -> {
                    var commitment = commitmentMapper.selectByIdForUpdate(organizationId, id);
                    if (commitment == null) {
                        throw notFound("Commitment", id);
                    }
                    return commitment;
                }).toList();
    }

    @Override
    public void incrementActual(long organizationId, long budgetId, BigDecimal signedAmount,
            Instant now) {
        if (budgetMapper.incrementActual(organizationId, budgetId, signedAmount, now) != 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Budget actual update conflict",
                    "The selected budget is missing or no longer ACTIVE.");
        }
    }

    private static DomainException notFound(String type, long id) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                type + " not found", type + " " + id + " is not available in the current organization.");
    }
}
