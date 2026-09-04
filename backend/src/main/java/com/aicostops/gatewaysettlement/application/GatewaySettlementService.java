package com.aicostops.gatewaysettlement.application;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.budget.application.CommitmentConsumeService;
import com.aicostops.budget.application.LedgerBudgetPort;
import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.gatewaysettlement.application.GatewaySettlementCostCalculator.CostResult;
import com.aicostops.gatewaysettlement.domain.GatewayReservation;
import com.aicostops.gatewaysettlement.domain.GatewaySettlement;
import com.aicostops.gatewaysettlement.domain.GatewaySettlementStatus;
import com.aicostops.gatewaysettlement.infrastructure.GatewayReservationSettlementMapper;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.ledger.application.GatewaySettlementLedgerPort;
import com.aicostops.ledger.application.GatewaySettlementLedgerPort.PostCommand;
import com.aicostops.ledger.application.GatewaySettlementLedgerService;
import com.aicostops.ledger.infrastructure.LedgerPostingMapper;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.shared.web.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic Gateway financial settlement orchestration. */
@Service
public final class GatewaySettlementService {

    private final GatewaySettlementMapper settlements;
    private final GatewaySettlementLineageReader lineageReader;
    private final GatewaySettlementCostCalculator calculator;
    private final BillingPeriodFinancialWriteFence periodFence;
    private final LedgerBudgetPort budgets;
    private final GatewayReservationSettlementMapper reservations;
    private final GatewaySettlementLedgerPort gatewayLedger;
    private final LedgerPostingMapper ledger;
    private final CommitmentConsumeService commitmentConsume;
    private final GatewaySettlementAuditPort audit;
    private final GatewaySettlementFailureInjector failureInjector;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public GatewaySettlementService(
            GatewaySettlementMapper settlements,
            GatewaySettlementLineageReader lineageReader,
            GatewaySettlementCostCalculator calculator,
            BillingPeriodFinancialWriteFence periodFence,
            LedgerBudgetPort budgets,
            GatewayReservationSettlementMapper reservations,
            GatewaySettlementLedgerService gatewayLedger,
            LedgerPostingMapper ledger,
            CommitmentConsumeService commitmentConsume,
            GatewaySettlementAuditPort audit,
            GatewaySettlementFailureInjector failureInjector,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.settlements = settlements;
        this.lineageReader = lineageReader;
        this.calculator = calculator;
        this.periodFence = periodFence;
        this.budgets = budgets;
        this.reservations = reservations;
        this.gatewayLedger = gatewayLedger;
        this.ledger = ledger;
        this.commitmentConsume = commitmentConsume;
        this.audit = audit;
        this.failureInjector = failureInjector;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Convenient constructor for focused unit tests. */
    public GatewaySettlementService(
            GatewaySettlementMapper settlements,
            GatewaySettlementLineageReader lineageReader,
            GatewaySettlementCostCalculator calculator,
            BillingPeriodFinancialWriteFence periodFence,
            LedgerBudgetPort budgets,
            GatewayReservationSettlementMapper reservations,
            GatewaySettlementLedgerService gatewayLedger,
            LedgerPostingMapper ledger,
            CommitmentConsumeService commitmentConsume,
            GatewaySettlementAuditPort audit,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(settlements, lineageReader, calculator, periodFence, budgets, reservations,
                gatewayLedger, ledger, commitmentConsume, audit,
                GatewaySettlementFailureInjector.noop(), metrics, transactionManager, clock);
    }

    public SettlementResult settle(long organizationId, long settlementId) {
        var before = settlements.selectById(organizationId, settlementId);
        if (before == null) {
            throw new IllegalArgumentException("Gateway Settlement is not available.");
        }
        if (before.status().isTerminal()) {
            return result(before);
        }

        var reservation = before.reservationId() == null ? null
                : reservations.selectById(organizationId, before.reservationId());
        if (before.reservationId() != null && reservation == null) {
            return markReconciliation(organizationId, settlementId, "RESERVATION_LINEAGE_MISSING");
        }

        try {
            var settled = transactions.execute(status -> settleInTransaction(
                    organizationId, before, reservation));
            if (settled == null) {
                throw new IllegalStateException("Settlement transaction returned no result");
            }
            metrics.gatewaySettlement("SETTLED", "SUCCESS");
            return settled;
        } catch (GatewaySettlementReconciliationException reconciliation) {
            return markReconciliation(organizationId, settlementId, reconciliation.errorCode());
        }
    }

    private SettlementResult settleInTransaction(long organizationId,
            GatewaySettlement before, GatewayReservation preReservation) {
        final BillingPeriod period;
        try {
            // The first financial lock is the same BillingPeriod row used by Close.
            period = periodFence.lockOpenById(organizationId, before.billingPeriodId());
        } catch (DomainException periodFailure) {
            throw reconcile("BILLING_PERIOD_NOT_OPEN", "Settlement period is not open");
        }
        failureInjector.after("BILLING_PERIOD_LOCKED");

        var preBudgetId = preReservation == null ? null : preReservation.budgetId();
        final List<Budget> lockedBudgets;
        try {
            lockedBudgets = preBudgetId == null ? List.of()
                    : budgets.lockBudgets(organizationId, List.of(preBudgetId));
        } catch (DomainException budgetFailure) {
            throw reconcile("BUDGET_LINEAGE_CONFLICT", "Bound Budget cannot be locked");
        }
        var lockedBudget = lockedBudgets.isEmpty() ? null : lockedBudgets.getFirst();

        var preCommitmentId = preReservation == null ? null : preReservation.commitmentId();
        final List<BudgetCommitment> lockedCommitments;
        try {
            lockedCommitments = preCommitmentId == null ? List.of()
                    : budgets.lockCommitments(organizationId, List.of(preCommitmentId));
        } catch (DomainException commitmentFailure) {
            throw reconcile("COMMITMENT_LINEAGE_CONFLICT", "Bound Commitment cannot be locked");
        }
        var lockedCommitment = lockedCommitments.isEmpty() ? null : lockedCommitments.getFirst();

        // Frozen lock order: BillingPeriod -> Budget -> Commitment -> Reservation -> Settlement.
        var lockedReservation = preReservation == null ? null
                : reservations.selectByIdForUpdate(organizationId, preReservation.id());
        var settlement = settlements.selectByIdForUpdate(organizationId, before.id());
        if (settlement == null) {
            throw reconcile("SETTLEMENT_LINEAGE_MISSING", "Settlement disappeared while processing");
        }
        if (settlement.status().isTerminal()) {
            return result(settlement);
        }
        if (!settlement.status().isAutomaticCandidate()) {
            throw reconcile("SETTLEMENT_STATE_CONFLICT", "Settlement is not automatically processable");
        }
        validateImmutableIdentity(before, settlement);
        validatePeriod(period.id(), settlement.billingPeriodId());
        validateReservation(settlement, preReservation, lockedReservation, lockedBudget,
                lockedCommitment);

        final GatewaySettlementLineageReader.SettlementLineage lineage;
        try {
            lineage = lineageReader.read(organizationId, settlement.id());
        } catch (IllegalArgumentException missingLineage) {
            throw reconcile("FROZEN_LINEAGE_MISMATCH", "Frozen Gateway lineage is not readable");
        }
        validateLineage(settlement, lineage.row());
        final CostResult cost;
        try {
            cost = calculator.calculate(new GatewaySettlementCostCalculator.CostInput(
                    lineage.row().pricingCurrency(), lineage.rates(), lineage.quantities()));
        } catch (IllegalArgumentException invalidCost) {
            throw reconcile("FROZEN_COST_INVALID", "Frozen Gateway cost is not settleable");
        }

        final long postingId;
        try {
            postingId = gatewayLedger.post(new PostCommand(organizationId, settlement.id(),
                    settlement.billingPeriodId(), cost.postedAmount(), settlement.currency(),
                    settlement.financialScopeType(), settlement.financialScopeId(),
                    lockedBudget == null ? null : lockedBudget.id(), Instant.now(clock)));
        } catch (IllegalArgumentException | DataIntegrityViolationException invalidLedger) {
            throw reconcile("LEDGER_LINEAGE_CONFLICT", "Gateway Ledger lineage is inconsistent");
        }
        failureInjector.after("LEDGER_INSERTED");

        var entries = ledger.selectEntriesByPostingId(organizationId, postingId);
        if (entries.size() != 1) {
            throw reconcile("LEDGER_ENTRY_CARDINALITY", "Gateway Settlement Ledger entry is incomplete");
        }
        var entryId = entries.getFirst().id();
        if (lockedBudget != null) {
            try {
                budgets.incrementActual(organizationId, lockedBudget.id(), cost.postedAmount(),
                        Instant.now(clock));
            } catch (DomainException budgetFailure) {
                throw reconcile("BUDGET_LINEAGE_CONFLICT", "Budget Actual cannot be updated");
            }
            failureInjector.after("BUDGET_ACTUAL_MUTATED");
        }

        if (lockedCommitment != null) {
            try {
                commitmentConsume.consume(new CommitmentConsumeService.ConsumeCommand(
                        organizationId, lockedCommitment.id(), cost.postedAmount(), entryId));
            } catch (DomainException commitmentFailure) {
                throw reconcile("COMMITMENT_LINEAGE_CONFLICT", "Explicit commitment cannot be consumed");
            }
        }

        var overrun = lockedReservation != null
                && cost.postedAmount().compareTo(lockedReservation.reservedAmount()) > 0;
        if (overrun) {
            metrics.gatewayReservationOverrun(lineage.row().providerCode());
        }
        audit.settlementPosted(organizationId, settlement.id(), settlement.requestId(),
                settlement.usageFactId(), settlement.routeAttemptId(), settlement.providerAccountId(),
                settlement.providerModelId(), settlement.pricingVersionId(), settlement.financialScopeType(),
                settlement.financialScopeId(), cost.postedAmount(), settlement.currency(),
                settlement.reservationId(), overrun);
        failureInjector.after("AUDIT_WRITTEN");

        if (lockedReservation != null) {
            if (reservations.finalizeForSettlement(organizationId, lockedReservation.id(),
                    lockedReservation.version(), Instant.now(clock)) != 1) {
                throw reconcile("RESERVATION_FINALIZATION_CONFLICT",
                        "Bound reservation was not ACTIVE or PENDING_HOLD");
            }
        }
        failureInjector.after("BEFORE_SETTLEMENT_SETTLED");
        if (settlements.markSettled(organizationId, settlement.id(), cost.calculatedAmountRaw(),
                cost.postedAmount(), cost.roundingDelta(), postingId, Instant.now(clock)) != 1) {
            throw reconcile("SETTLEMENT_STATE_CONFLICT", "Settlement could not become SETTLED");
        }
        var committed = settlements.selectById(organizationId, settlement.id());
        if (committed == null) {
            throw new IllegalStateException("A committed Settlement must be readable");
        }
        return new SettlementResult(committed, postingId, entryId);
    }

    private SettlementResult markReconciliation(long organizationId, long settlementId,
            String errorCode) {
        var now = Instant.now(clock);
        transactions.executeWithoutResult(status -> settlements.markReconciliationRequired(
                organizationId, settlementId, boundedErrorCode(errorCode), now));
        var current = settlements.selectById(organizationId, settlementId);
        if (current == null) {
            throw new IllegalStateException("A reconciliation Settlement must be readable");
        }
        metrics.gatewaySettlement("RECONCILIATION_REQUIRED", "RECONCILIATION");
        return result(current);
    }

    private static void validateImmutableIdentity(GatewaySettlement expected,
            GatewaySettlement actual) {
        if (expected.id() != actual.id()
                || expected.organizationId() != actual.organizationId()
                || !expected.settlementKey().equals(actual.settlementKey())
                || expected.requestId() != actual.requestId()
                || expected.routeAttemptId() != actual.routeAttemptId()
                || expected.usageFactId() != actual.usageFactId()
                || !Objects.equals(expected.reservationId(), actual.reservationId())
                || expected.billingPeriodId() != actual.billingPeriodId()
                || !expected.financialScopeType().equals(actual.financialScopeType())
                || expected.financialScopeId() != actual.financialScopeId()
                || expected.providerAccountId() != actual.providerAccountId()
                || expected.providerModelId() != actual.providerModelId()
                || expected.pricingVersionId() != actual.pricingVersionId()
                || !expected.currency().equals(actual.currency())) {
            throw reconcile("SETTLEMENT_LINEAGE_CHANGED", "Settlement immutable lineage changed");
        }
    }

    private static void validatePeriod(long periodId, long settlementPeriodId) {
        if (periodId != settlementPeriodId) {
            throw reconcile("BILLING_PERIOD_LINEAGE_CONFLICT", "Settlement period binding changed");
        }
    }

    private static void validateReservation(GatewaySettlement settlement,
            GatewayReservation pre, GatewayReservation locked, Budget budget,
            BudgetCommitment commitment) {
        if (settlement.reservationId() == null) {
            if (pre != null || locked != null || budget != null || commitment != null) {
                throw reconcile("RESERVATION_LINEAGE_CONFLICT", "Unbudgeted settlement acquired a reservation");
            }
            return;
        }
        if (pre == null || locked == null || pre.id() != locked.id()
                || locked.requestId() != settlement.requestId()
                || locked.routeAttemptId() != settlement.routeAttemptId()
                || locked.billingPeriodId() != settlement.billingPeriodId()
                || locked.budgetId() != budgetId(budget)
                || !locked.financialScopeType().equals(settlement.financialScopeType())
                || locked.financialScopeId() != settlement.financialScopeId()
                || !locked.currency().equals(settlement.currency())) {
            throw reconcile("RESERVATION_LINEAGE_CONFLICT", "Bound reservation lineage does not match");
        }
        if (!"ACTIVE".equals(locked.status()) && !"PENDING_HOLD".equals(locked.status())) {
            throw reconcile("RESERVATION_NOT_SETTLEABLE", "Bound reservation is not active or pending hold");
        }
        if (budget == null || budget.id() != locked.budgetId()
                || budget.billingPeriodId() != locked.billingPeriodId()
                || !budget.currency().equals(locked.currency())) {
            throw reconcile("BUDGET_LINEAGE_CONFLICT", "Bound reservation Budget does not match");
        }
        if (locked.commitmentId() == null) {
            if (commitment != null) {
                throw reconcile("COMMITMENT_LINEAGE_CONFLICT", "Commitment was not explicitly bound");
            }
        } else if (commitment == null || commitment.budgetId() != budget.id()
                || !commitment.status().canConsume()) {
            throw reconcile("COMMITMENT_LINEAGE_CONFLICT", "Explicit commitment is not consumable");
        }
    }

    private static void validateLineage(GatewaySettlement settlement,
            GatewaySettlementMapper.LineageRow row) {
        if (row.requestId() != settlement.requestId()
                || row.routeAttemptId() != settlement.routeAttemptId()
                || row.usageFactId() != settlement.usageFactId()
                || !Objects.equals(row.currentUsageFactId(), settlement.usageFactId())
                || !"FINAL".equals(row.usageStatus())
                || row.usageRouteAttemptId() != settlement.routeAttemptId()
                || row.usagePricingVersionId() != settlement.pricingVersionId()
                || !settlement.currency().equals(row.usageCurrency())
                || !Objects.equals(row.currentRouteAttemptId(), settlement.routeAttemptId())
                || row.attemptProviderAccountId() != settlement.providerAccountId()
                || row.attemptProviderModelId() != settlement.providerModelId()
                || row.attemptPricingVersionId() != settlement.pricingVersionId()
                || row.requestBillingPeriodId() == null
                || row.requestBillingPeriodId() != settlement.billingPeriodId()
                || !settlement.financialScopeType().equals(row.requestFinancialScopeType())
                || row.requestFinancialScopeId() == null
                || row.requestFinancialScopeId() != settlement.financialScopeId()
                || !settlement.currency().equals(row.pricingCurrency())) {
            throw reconcile("FROZEN_LINEAGE_MISMATCH", "Usage, route, request or pricing lineage changed");
        }
    }

    private static long budgetId(Budget budget) {
        return budget == null ? -1 : budget.id();
    }

    private static GatewaySettlementReconciliationException reconcile(String code, String message) {
        return new GatewaySettlementReconciliationException(boundedErrorCode(code), message);
    }

    private static String boundedErrorCode(String code) {
        if (code == null || code.isBlank()) {
            return "SETTLEMENT_RECONCILIATION_REQUIRED";
        }
        var normalized = code.strip().toUpperCase(java.util.Locale.ROOT);
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static SettlementResult result(GatewaySettlement settlement) {
        return new SettlementResult(settlement, settlement.ledgerPostingId(), null);
    }

    public record SettlementResult(
            GatewaySettlement settlement,
            Long ledgerPostingId,
            Long ledgerEntryId) {
    }
}
