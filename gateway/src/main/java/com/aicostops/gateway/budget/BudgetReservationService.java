package com.aicostops.gateway.budget;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.cache.GatewayConfigCache;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * M12/HC-04 TX1: MySQL-authoritative budget admission.
 *
 * <p>Pricing rate lookup and conservative upper-bound calculation plus the
 * fast-path idempotency check run before TX1 so the short MySQL transaction
 * only takes the shared OPEN BillingPeriod fence, resolves the Budget, atomically
 * updates {@code budget.reserved_amount} via conditional SQL (which acquires
 * the Budget X lock and replaces {@code lockBudgetById} + {@code sumEffectiveReservations}),
 * inserts the ACTIVE reservation with {@code useGeneratedKeys}, and marks the
 * request {@code RESERVED} (or {@code REJECTED_BUDGET} when insufficient).
 *
 * <p>{@code markRequestReserved} intentionally remains inside TX1 because
 * {@code GatewayCloseBlockerMapper.countUnresolvedReservations} joins
 * {@code gateway_request.billing_period_id} to detect ACTIVE holds during
 * Period Close, and its state predicate fences invalid concurrent terminal
 * transitions.
 *
 * <p>TX1 runs under READ COMMITTED so post-update re-reads (duplicate-winner
 * convergence and budget status re-read after 0-row conditional UPDATE) observe
 * committed mutations made by concurrent transactions.
 */
@Service
public class BudgetReservationService {

    private final BudgetReservationMapper reservationMapper;
    private final GatewayRequestMapper requestMapper;
    private final GatewayProperties properties;
    private final BlockingIoScheduler blockingIo;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final BudgetAdmissionSemaphore budgetSemaphore;
    private final GatewayConfigCache configCache;
    private final ReservationDelayQueue delayQueue;

    public BudgetReservationService(
            BudgetReservationMapper reservationMapper,
            GatewayRequestMapper requestMapper,
            GatewayProperties properties,
            BlockingIoScheduler blockingIo,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(reservationMapper, requestMapper, properties, blockingIo, transactionManager, clock,
                new BudgetAdmissionSemaphore(properties), null, null);
    }

    public BudgetReservationService(
            BudgetReservationMapper reservationMapper,
            GatewayRequestMapper requestMapper,
            GatewayProperties properties,
            BlockingIoScheduler blockingIo,
            PlatformTransactionManager transactionManager,
            Clock clock,
            BudgetAdmissionSemaphore budgetSemaphore) {
        this(reservationMapper, requestMapper, properties, blockingIo, transactionManager, clock,
                budgetSemaphore, null, null);
    }

    @Autowired
    public BudgetReservationService(
            BudgetReservationMapper reservationMapper,
            GatewayRequestMapper requestMapper,
            GatewayProperties properties,
            BlockingIoScheduler blockingIo,
            PlatformTransactionManager transactionManager,
            Clock clock,
            BudgetAdmissionSemaphore budgetSemaphore,
            GatewayConfigCache configCache,
            ReservationDelayQueue delayQueue) {
        this.reservationMapper = reservationMapper;
        this.requestMapper = requestMapper;
        this.properties = properties;
        this.blockingIo = blockingIo;
        this.transactions = new TransactionTemplate(transactionManager);
        // TX1 runs under READ COMMITTED so re-reads after a 0-row conditional
        // UPDATE or DuplicateKeyException see the concurrent winner's committed
        // reservation and budget state rather than an earlier REPEATABLE READ view.
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
        this.budgetSemaphore = budgetSemaphore;
        this.configCache = configCache;
        this.delayQueue = delayQueue;
    }

    public Mono<AdmissionResult> admit(AdmissionCommand command) {
        // Explicit transaction template: admitInTx is invoked through this
        // lambda on the blocking-DB thread, and a self-invoked @Transactional
        // method would bypass the Spring proxy and run without a transaction.
        return blockingIo.call(() -> admitWithPrecompute(command));
    }

    /**
     * Synchronous TX1 entry for callers already on the blocking-DB scheduler
     * (e.g. GatewayRequestService). Never block on {@link #admit} from such a
     * thread: re-submitting to the same bounded scheduler risks deadlock.
     */
    public AdmissionResult admitSync(AdmissionCommand command) {
        return admitWithPrecompute(command);
    }

    private AdmissionResult admitWithPrecompute(AdmissionCommand command) {
        var principal = command.principal();
        var orgId = principal.organizationId();

        // Fast-path idempotent replay read before opening TX1 / acquiring any locks.
        var fastExisting = findOrFenceExistingHold(command);
        if (fastExisting != null) {
            return fastExisting;
        }

        // Pre-compute conservative bound outside TX1 / Budget X lock. Do not
        // short-circuit on ReservationBoundException yet: periodStatus != OPEN
        // and noBudget (UNBUDGETED / REJECTED_BUDGET) take precedence inside TX1.
        var rates = configCache != null
                ? configCache.getPricingRates(orgId, command.pricingVersionId())
                : reservationMapper.findPricingRates(orgId, command.pricingVersionId());
        var calculatorRates = new ArrayList<ReservationAmountCalculator.PricingRate>(rates.size());
        for (var rate : rates) {
            calculatorRates.add(new ReservationAmountCalculator.PricingRate(
                    rate.dimensionCode(), rate.unitQuantity(), rate.unitPrice()));
        }
        BigDecimal precomputedAmount = null;
        boolean boundImpossible = false;
        try {
            precomputedAmount = ReservationAmountCalculator.calculate(
                    calculatorRates, command.effectiveMaxOutputTokens());
        } catch (ReservationBoundException ex) {
            boundImpossible = true;
        }

        final var amountToReserve = precomputedAmount;
        final var impossible = boundImpossible;
        // Resolve the target Budget in a brief read-only lookup so its Hikari
        // connection returns to the pool before waiting on the per-budget
        // semaphore. If no Budget matches, enter TX1 directly for noBudget().
        var preResolvedBudget = resolveBudget(
                orgId, command.billingPeriodId(), principal, command.currency());
        var permit = preResolvedBudget == null ? null : budgetSemaphore.acquire(preResolvedBudget.id());
        AdmissionResult result;
        try {
            result = transactions.execute(status -> admitInTx(command, amountToReserve, impossible));
        } finally {
            if (permit != null) {
                permit.release();
            }
        }
        if (delayQueue != null && result.expiresAt() != null) {
            delayQueue.enqueue(result.reservationId(), result.expiresAt());
        }
        return result;
    }

    AdmissionResult admitInTx(
            AdmissionCommand command, BigDecimal reservedAmount, boolean boundImpossible) {
        var principal = command.principal();
        var orgId = principal.organizationId();
        var now = Instant.now(clock);

        // 1. Shared OPEN BillingPeriod fence: concurrent admissions for this
        // period proceed while Close/Reopen keep the exclusive lock. TX1 never
        // mutates billing_period, so this lock cannot upgrade.
        var periodStatus = requestMapper.lockBillingPeriodShared(command.billingPeriodId(), orgId);
        if (periodStatus == null || !"OPEN".equals(periodStatus)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Billing period is not open for reservation");
        }

        // 2. Resolve exact Budget, else ORG fallback, in the pricing currency.
        // No FX: a different-currency Budget is no matching Budget.
        var budget = resolveBudget(orgId, command.billingPeriodId(), principal, command.currency());
        if (budget == null) {
            return noBudget(principal, command);
        }
        if (!"ACTIVE".equals(budget.status())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Budget is not available for reservation");
        }
        if (boundImpossible || reservedAmount == null) {
            return reservationImpossible(principal, command);
        }

        // 3. Atomic conditional UPDATE acquires the Budget row X lock and checks
        // remaining headroom: total - actual - committed - reserved >= amount.
        if (reservationMapper.reserveBudgetAmount(orgId, budget.id(), reservedAmount) != 1) {
            var existing = findOrFenceExistingHold(command);
            if (existing != null) {
                return existing;
            }
            var latestBudget = reservationMapper.selectBudgetById(orgId, budget.id());
            if (latestBudget == null || !"ACTIVE".equals(latestBudget.status())) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "Budget is not available for reservation");
            }
            return insufficient(principal, command);
        }

        // 4. Insert the ACTIVE hold under the held Budget X lock; a concurrent
        // winner reverts this transaction's increment before converging via replay.
        var expiresAt = now.plusMillis(properties.getReservationTtlMs());
        var insert = new BudgetReservationMapper.ReservationInsert(
                orgId, command.requestId(), command.routeAttemptId(),
                command.billingPeriodId(), budget.id(),
                principal.financialScopeType(), principal.financialScopeId(),
                command.currency(), reservedAmount, expiresAt);
        try {
            reservationMapper.insertActiveReservation(insert);
        } catch (DuplicateKeyException ex) {
            if (reservationMapper.releaseReservedBudgetAmount(
                    orgId, budget.id(), reservedAmount) != 1) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "Reservation race could not revert budget increment");
            }
            var winner = reservationMapper.findByRouteAttempt(orgId, command.routeAttemptId());
            if (winner == null) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "Reservation race could not converge");
            }
            return replayExisting(command, winner);
        }
        var reservationId = insert.getId();
        if (reservationId <= 0) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Reservation id was not generated");
        }

        if (reservationMapper.markRequestReserved(
                command.requestId(), orgId, command.billingPeriodId(), command.routeAttemptId()) != 1) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Request cannot reach reserved state");
        }
        return new AdmissionResult(AdmissionOutcome.RESERVED, reservationId,
                budget.id(), reservedAmount, expiresAt);
    }

    private AdmissionResult noBudget(GatewayPrincipal principal, AdmissionCommand command) {
        if ("REQUIRED".equals(principal.budgetEnforcementMode())) {
            persistTerminalBudgetRejection(command);
            // Terminal business result: persist REJECTED_BUDGET inside TX1 and
            // return it. Throwing here would roll the rejection back; the
            // caller maps the outcome to GATEWAY_BUDGET_EXHAUSTED after commit.
            // Candidate admission is not request exhaustion. The caller may
            // safely release this route and evaluate the next frozen policy
            // candidate before a request-level rejection is persisted.
            return new AdmissionResult(AdmissionOutcome.REJECTED_BUDGET, -1L, -1L, null);
        }
        // OPTIONAL without a matching Budget: explicitly allowed unbudgeted.
        return new AdmissionResult(AdmissionOutcome.UNBUDGETED, -1L, -1L, null);
    }

    private AdmissionResult reservationImpossible(
            GatewayPrincipal principal, AdmissionCommand command) {
        // The bound cannot be safely evaluated: fail closed for both modes
        // (an existing Budget must be reserved against, never bypassed).
        // Persist the terminal rejection inside TX1 and let the caller map it
        // after commit: REQUIRED -> DEPENDENCY_UNAVAILABLE (unsafe bound),
        // OPTIONAL -> BUDGET_EXHAUSTED (existing budget cannot be bypassed).
        // A non-evaluable bound is a SAFE candidate outcome; request-level
        // rejection is decided only after deterministic candidate exhaustion.
        if ("REQUIRED".equals(principal.budgetEnforcementMode())) {
            return new AdmissionResult(AdmissionOutcome.REJECTED_DEPENDENCY, -1L, -1L, null);
        }
        return new AdmissionResult(AdmissionOutcome.REJECTED_BUDGET, -1L, -1L, null);
    }

    private AdmissionResult insufficient(
            GatewayPrincipal principal, AdmissionCommand command) {
        persistTerminalBudgetRejection(command);
        return new AdmissionResult(AdmissionOutcome.REJECTED_BUDGET, -1L, -1L, null);
    }

    private void persistTerminalBudgetRejection(AdmissionCommand command) {
        if (command.candidateOnly()) {
            return;
        }
        if (requestMapper.markRequestRejectedBudget(
                command.requestId(), command.principal().organizationId(), command.billingPeriodId()) != 1) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Request cannot reach rejected budget state");
        }
    }

    private AdmissionResult replayExisting(
            AdmissionCommand command,
            BudgetReservationMapper.ReservationRow existing) {
        if (!"ACTIVE".equals(existing.status())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Reservation is no longer active for this route attempt");
        }
        if (existing.budgetId() != command.expectedBudgetId()
                && command.expectedBudgetId() >= 0) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Reservation budget does not match this request context");
        }
        return new AdmissionResult(AdmissionOutcome.RESERVED, existing.id(),
                existing.budgetId(), existing.reservedAmount());
    }

    private AdmissionResult findOrFenceExistingHold(AdmissionCommand command) {
        var orgId = command.principal().organizationId();
        var existing = reservationMapper.findByRouteAttempt(orgId, command.routeAttemptId());
        if (existing != null) {
            return replayExisting(command, existing);
        }
        if (reservationMapper.countEffectiveHolds(orgId, command.requestId()) > 0) {
            var raceWinner = reservationMapper.findByRouteAttempt(orgId, command.routeAttemptId());
            if (raceWinner != null) {
                return replayExisting(command, raceWinner);
            }
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "An effective reservation already exists for this request");
        }
        return null;
    }

    private BudgetReservationMapper.BudgetRow resolveBudget(
            long orgId, long billingPeriodId, GatewayPrincipal principal, String currency) {
        var budget = reservationMapper.selectBudgetByIdentity(orgId, billingPeriodId,
                principal.financialScopeType(), principal.financialScopeId(), currency);
        if (budget == null && !isOrgScope(principal)) {
            budget = reservationMapper.selectBudgetByIdentity(orgId, billingPeriodId,
                    "ORG", orgId, currency);
        }
        return budget;
    }

    private static boolean isOrgScope(GatewayPrincipal principal) {
        return "ORG".equals(principal.financialScopeType());
    }

    public record AdmissionCommand(
            GatewayPrincipal principal,
            long requestId,
            long routeAttemptId,
            long billingPeriodId,
            long pricingVersionId,
            String currency,
            long effectiveMaxOutputTokens,
            long expectedBudgetId,
            boolean candidateOnly) {
        public AdmissionCommand(
                GatewayPrincipal principal,
                long requestId,
                long routeAttemptId,
                long billingPeriodId,
                long pricingVersionId,
                String currency,
                long effectiveMaxOutputTokens,
                long expectedBudgetId) {
            this(principal, requestId, routeAttemptId, billingPeriodId, pricingVersionId,
                    currency, effectiveMaxOutputTokens, expectedBudgetId, false);
        }
    }

    public record AdmissionResult(
            AdmissionOutcome outcome,
            long reservationId,
            long budgetId,
            BigDecimal reservedAmount,
            Instant expiresAt) {
        public AdmissionResult(AdmissionOutcome outcome, long reservationId, long budgetId,
                BigDecimal reservedAmount) {
            this(outcome, reservationId, budgetId, reservedAmount, null);
        }
    }

    public enum AdmissionOutcome {
        RESERVED,
        UNBUDGETED,
        REJECTED_BUDGET,
        REJECTED_DEPENDENCY
    }
}
