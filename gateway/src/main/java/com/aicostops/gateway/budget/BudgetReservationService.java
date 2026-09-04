package com.aicostops.gateway.budget;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * M12 TX1: MySQL-authoritative budget admission.
 *
 * <p>One short synchronous MySQL transaction (offloaded from Reactor Netty):
 * lock OPEN BillingPeriod, resolve exact Budget or ORG fallback in the pricing
 * currency, lock the Budget row, observe Total/Actual/Committed plus effective
 * ACTIVE/PENDING_HOLD reservations under the same lock, then insert the ACTIVE
 * reservation and move VALIDATED to RESERVED (or to REJECTED_BUDGET when
 * insufficient). No Provider network I/O happens inside or before this
 * transaction commits.
 */
@Service
public class BudgetReservationService {

    private final BudgetReservationMapper reservationMapper;
    private final GatewayRequestMapper requestMapper;
    private final GatewayPropertiesBridge properties;
    private final BlockingIoScheduler blockingIo;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BudgetReservationService(
            BudgetReservationMapper reservationMapper,
            GatewayRequestMapper requestMapper,
            GatewayPropertiesBridge properties,
            BlockingIoScheduler blockingIo,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.reservationMapper = reservationMapper;
        this.requestMapper = requestMapper;
        this.properties = properties;
        this.blockingIo = blockingIo;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Mono<AdmissionResult> admit(AdmissionCommand command) {
        // Explicit transaction template: admitBlocking is invoked through this
        // lambda on the blocking-DB thread, and a self-invoked @Transactional
        // method would bypass the Spring proxy and run without a transaction.
        return blockingIo.call(() -> transactions.execute(status -> admitBlocking(command)));
    }

    /**
     * Synchronous TX1 entry for callers already on the blocking-DB scheduler
     * (e.g. GatewayRequestService). Never block on {@link #admit} from such a
     * thread: re-submitting to the same bounded scheduler risks deadlock.
     */
    public AdmissionResult admitSync(AdmissionCommand command) {
        return transactions.execute(status -> admitBlocking(command));
    }

    AdmissionResult admitBlocking(AdmissionCommand command) {
        var principal = command.principal();
        var orgId = principal.organizationId();
        var now = Instant.now(clock);

        // 1. Lock the OPEN BillingPeriod: dispatch and Close serialize here.
        var periodStatus = requestMapper.lockBillingPeriod(command.billingPeriodId(), orgId);
        if (periodStatus == null || !"OPEN".equals(periodStatus)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Billing period is not open for reservation");
        }

        // 2. Resolve exact Budget, else ORG fallback, in the pricing currency.
        // No FX: a different-currency Budget is no matching Budget.
        var budget = reservationMapper.selectBudgetByIdentity(orgId, command.billingPeriodId(),
                principal.financialScopeType(), principal.financialScopeId(), command.currency());
        if (budget == null && !isOrgScope(principal)) {
            budget = reservationMapper.selectBudgetByIdentity(orgId, command.billingPeriodId(),
                    "ORG", orgId, command.currency());
        }
        if (budget == null) {
            return noBudget(principal, command);
        }

        // 3. Lock the Budget row: reservation serializes with V1 Actual mutation.
        var locked = reservationMapper.lockBudgetById(orgId, budget.id());
        if (locked == null || !"ACTIVE".equals(locked.status())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Budget is not available for reservation");
        }

        // 4. Idempotent replay: the same route attempt converges, never holds twice.
        var existing = reservationMapper.findByRouteAttempt(orgId, command.routeAttemptId());
        if (existing != null) {
            return replayExisting(command, existing);
        }
        if (reservationMapper.countEffectiveHolds(orgId, command.requestId()) > 0) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "An effective reservation already exists for this request");
        }

        // 5. Conservative upper bound from the frozen Pricing Version.
        var rates = reservationMapper.findPricingRates(orgId, command.pricingVersionId());
        var calculatorRates = new ArrayList<ReservationAmountCalculator.PricingRate>(rates.size());
        for (var rate : rates) {
            calculatorRates.add(new ReservationAmountCalculator.PricingRate(
                    rate.dimensionCode(), rate.unitQuantity(), rate.unitPrice()));
        }
        BigDecimal reservedAmount;
        try {
            reservedAmount = ReservationAmountCalculator.calculate(
                    calculatorRates, command.effectiveMaxOutputTokens());
        } catch (ReservationBoundException ex) {
            return reservationImpossible(principal, command);
        }

        // 6. Realtime Available under the same Budget lock.
        var effectiveHolds = reservationMapper.sumEffectiveReservations(orgId, locked.id());
        if (effectiveHolds == null) {
            effectiveHolds = BigDecimal.ZERO;
        }
        var available = locked.totalAmount()
                .subtract(locked.actualAmount())
                .subtract(locked.committedAmount())
                .subtract(effectiveHolds);

        if (available.compareTo(reservedAmount) < 0) {
            return insufficient(principal, command);
        }

        // 7. Insert the ACTIVE hold; a concurrent winner converges via replay.
        var expiresAt = now.plusMillis(properties.reservationTtlMs());
        try {
            reservationMapper.insertActiveReservation(new BudgetReservationMapper.ReservationInsert(
                    orgId, command.requestId(), command.routeAttemptId(),
                    command.billingPeriodId(), locked.id(),
                    principal.financialScopeType(), principal.financialScopeId(),
                    command.currency(), reservedAmount, expiresAt));
        } catch (DuplicateKeyException ex) {
            var winner = reservationMapper.findByRouteAttempt(orgId, command.routeAttemptId());
            if (winner == null) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "Reservation race could not converge");
            }
            return replayExisting(command, winner);
        }
        var reservationId = reservationMapper.lastInsertId();

        if (reservationMapper.markRequestReserved(
                command.requestId(), orgId, command.billingPeriodId(), command.routeAttemptId()) != 1) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Request cannot reach reserved state");
        }
        return new AdmissionResult(AdmissionOutcome.RESERVED, reservationId,
                locked.id(), reservedAmount);
    }

    private AdmissionResult noBudget(GatewayPrincipal principal, AdmissionCommand command) {
        if ("REQUIRED".equals(principal.budgetEnforcementMode())) {
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
        return new AdmissionResult(AdmissionOutcome.REJECTED_BUDGET, -1L, -1L, null);
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
            long expectedBudgetId) {
    }

    public record AdmissionResult(
            AdmissionOutcome outcome,
            long reservationId,
            long budgetId,
            BigDecimal reservedAmount) {
    }

    public enum AdmissionOutcome {
        RESERVED,
        UNBUDGETED,
        REJECTED_BUDGET,
        REJECTED_DEPENDENCY
    }
}
