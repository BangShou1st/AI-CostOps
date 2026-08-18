package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.budget.domain.BudgetCommitmentStatus;
import com.aicostops.budget.domain.BudgetDecimal;
import com.aicostops.budget.domain.BudgetStatus;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.budget.infrastructure.BudgetCommitmentMapper;
import com.aicostops.budget.infrastructure.BudgetMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AIC-045 consume primitive: an application-level financial building block
 * meant to be composed inside the future ledger posting transaction
 * (AIC-048) — deliberately NOT an HTTP command and NOT a user-facing budget
 * mutation.
 *
 * <p>The frozen formula is {@code consumed = min(entryAmount,
 * remainingAmount)}. The primitive moves exactly three things, all in the
 * caller's transaction: the public method is enforced with
 * {@code @Transactional(propagation = MANDATORY)}, so a direct call without
 * an existing transaction is rejected with
 * {@code IllegalTransactionStateException} before any mutation — the
 * consume primitive can never silently commit a half financial state.
 *
 * <pre>
 * commitment.remaining_amount -= consumed   (status → PARTIALLY_CONSUMED /
 *                                            CONSUMED)
 * budget.committed_amount    -= consumed    (outstanding commitment release)
 * budget_commitment_usage     += consumed   (append-only lineage, keyed by
 *                                            ledger_entry_id)
 * </pre>
 *
 * <p>{@code budget.actual_amount} is deliberately NOT touched here:
 * AIC-048 owns the actual-side posting (actual += full entry amount) and
 * will call this primitive in the same transaction, so AIC-045 must not
 * pre-post half of it. Entry amounts beyond the remaining become uncommitted
 * actual that AIC-048 still must post.
 *
 * <p>Lineage uniqueness is enforced twice: by the V11
 * {@code UNIQUE(org_id, budget_commitment_id, ledger_entry_id)} and by the
 * pre-check + duplicate-key fallback here, so the same ledger entry can
 * never consume the same commitment twice. {@code ledger_entry_id} has no FK
 * yet (ledger_entry arrives in AIC-047); tests use synthetic future ids.
 *
 * <p>Lock order matches the other financial commands: BillingPeriod → Budget
 * → Commitment.
 */
@Service
public class CommitmentConsumeService {

    private final BillingPeriodMapper billingPeriodMapper;
    private final BudgetMapper budgetMapper;
    private final BudgetCommitmentMapper commitmentMapper;
    private final CommitmentAuditPort audit;
    private final Clock clock;

    public CommitmentConsumeService(
            BillingPeriodMapper billingPeriodMapper,
            BudgetMapper budgetMapper,
            BudgetCommitmentMapper commitmentMapper,
            CommitmentAuditPort audit,
            Clock clock) {
        this.billingPeriodMapper = billingPeriodMapper;
        this.budgetMapper = budgetMapper;
        this.commitmentMapper = commitmentMapper;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Consumes up to {@code entryAmount} of the commitment's remaining
     * amount. MANDATORY: an existing transaction is required, so a caller
     * without one gets {@code IllegalTransactionStateException} before any
     * mutation — this primitive is designed to run inside the AIC-048
     * ledger posting transaction and must never open its own. Returns the
     * exact consumed amount and the resulting state. A replayed
     * ledgerEntry lineage returns the stored consumption without any side
     * effect, even when the commitment reached a terminal state.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ConsumeResult consume(ConsumeCommand command) {
        var entryAmount = requireMoney(command.entryAmount(), "entryAmount");
        if (entryAmount.signum() <= 0) {
            throw validation("entryAmount must be greater than zero.");
        }
        var commitment = commitmentMapper.selectByIdAndOrganization(
                command.organizationId(), command.commitmentId());
        if (commitment == null) {
            throw notFound("The commitment is not available in the current organization.");
        }
        var budget = budgetMapper.selectByIdAndOrganization(command.organizationId(),
                commitment.budgetId());
        if (budget == null) {
            throw notFound("The budget is not available in the current organization.");
        }
        var now = clock.instant();

        // Lock order: BillingPeriod → Budget → Commitment (frozen). The
        // frozen rule (04-transactions §10) gates on the period STATUS only:
        // the budget already binds this commitment to its period, so the
        // primitive must not re-gate the Source-determined posting period
        // with the current wall clock.
        var period = billingPeriodMapper.selectByIdForUpdate(command.organizationId(),
                budget.billingPeriodId());
        if (period == null) {
            throw stateConflict("Billing period is missing",
                    "The budget references no billing period; consumption requires one.");
        }
        if (period.status() != BillingPeriodStatus.OPEN) {
            throw periodNotOpen("The billing period of the budget is "
                    + period.status() + "; consumption requires an OPEN period.");
        }
        var budgetLocked = budgetMapper.selectByIdForUpdate(command.organizationId(),
                budget.id());
        if (budgetLocked.status() != BudgetStatus.ACTIVE) {
            throw stateConflict("Budget is not active",
                    "The budget must be ACTIVE before consumption.");
        }
        var commitmentLocked = commitmentMapper.selectByIdForUpdate(command.organizationId(),
                command.commitmentId());

        // Lineage replay FIRST: the same ledger entry has already consumed
        // this commitment — return the stored consumption with zero side
        // effects. The replay must also work for terminal states
        // (CONSUMED/RELEASED): a replayed ledger entry is not a new
        // consumption attempt, so it must never hit the state gate below.
        var existing = commitmentMapper.selectUsageAmountByLedgerEntry(
                command.organizationId(), command.commitmentId(), command.ledgerEntryId());
        if (existing != null) {
            return resultOf(commitmentLocked, existing);
        }
        // No lineage yet: this is a NEW ledger entry, so the commitment state
        // gate applies — an invalid state (terminal, REJECTED, CANCELED,
        // REQUESTED) can never be consumed by a fresh entry.
        if (!commitmentLocked.status().canConsume()) {
            throw stateConflict("Commitment cannot be consumed",
                    "Only an ACTIVE or PARTIALLY_CONSUMED commitment can be consumed; "
                            + "the commitment is " + commitmentLocked.status() + ".");
        }

        var consumed = entryAmount.min(commitmentLocked.remainingAmount());
        var targetStatus = consumed.compareTo(commitmentLocked.remainingAmount()) == 0
                ? BudgetCommitmentStatus.CONSUMED
                : BudgetCommitmentStatus.PARTIALLY_CONSUMED;

        try {
            commitmentMapper.insertUsage(command.organizationId(), command.commitmentId(),
                    command.ledgerEntryId(), consumed, now);
        } catch (DuplicateKeyException concurrentLineage) {
            // The concurrent winner committed the lineage row while we were
            // waiting on the commitment lock; re-read it (current state) and
            // replay instead of double-consuming.
            var winner = commitmentMapper.selectUsageAmountByLedgerEntry(
                    command.organizationId(), command.commitmentId(),
                    command.ledgerEntryId());
            if (winner == null) {
                throw concurrentLineage;
            }
            return resultOf(commitmentLocked, winner);
        }

        if (budgetMapper.decrementCommitted(command.organizationId(), budget.id(),
                consumed, now) != 1) {
            throw stateConflict("Budget consumption conflict",
                    "The committed counter cannot cover the consumed amount.");
        }
        if (commitmentMapper.updateConsume(command.organizationId(), command.commitmentId(),
                consumed, targetStatus.name(), now) != 1) {
            throw stateConflict("Commitment consumption conflict",
                    "The commitment was no longer consumable when the consumption applied.");
        }
        audit.consumed(command.organizationId(), null, command.commitmentId(),
                budget.id(), consumed, command.ledgerEntryId(),
                commitmentLocked.status().name(), targetStatus.name());
        return new ConsumeResult(command.commitmentId(), consumed,
                commitmentLocked.remainingAmount().subtract(consumed), targetStatus);
    }

    public record ConsumeCommand(
            long organizationId,
            long commitmentId,
            BigDecimal entryAmount,
            long ledgerEntryId) {
    }

    public record ConsumeResult(
            long commitmentId,
            BigDecimal consumedAmount,
            BigDecimal remainingAmount,
            BudgetCommitmentStatus status) {
    }

    private static ConsumeResult resultOf(BudgetCommitment commitment,
            BigDecimal consumedAmount) {
        return new ConsumeResult(commitment.id(), consumedAmount,
                commitment.remainingAmount(), commitment.status());
    }

    private static BigDecimal requireMoney(BigDecimal value, String field) {
        try {
            return BudgetDecimal.money(value);
        } catch (IllegalArgumentException notExactlyRepresentable) {
            throw validation(notExactlyRepresentable.getMessage());
        }
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Commitment validation failed", detail);
    }

    private static DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Commitment not found", detail);
    }

    private static DomainException stateConflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                title, detail);
    }

    private static DomainException periodNotOpen(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                "Billing period is not open", detail);
    }
}
