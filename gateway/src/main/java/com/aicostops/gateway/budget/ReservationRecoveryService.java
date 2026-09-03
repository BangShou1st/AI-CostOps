package com.aicostops.gateway.budget;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.observability.GatewayMetrics;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M12 bounded reservation recovery (TTL is a recovery trigger, not proof of
 * no cost).
 *
 * <p>Periodically scans expired ACTIVE reservations in bounded batches. Lock
 * order inside each recovery transaction: BillingPeriod, Budget, Reservation.
 * A definitively pre-dispatch hold (request RESERVED/VALIDATED, attempt
 * PLANNED, no DISPATCH_INTENT evidence) becomes RELEASED with the request
 * moved to FAILED_PRE_DISPATCH; anything that may have dispatched becomes
 * PENDING_HOLD and keeps holding money until Settlement/reconciliation.
 * Post-dispatch states are never released.
 */
@Component
public class ReservationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ReservationRecoveryService.class);

    private final BudgetReservationMapper reservationMapper;
    private final GatewayRequestMapper requestMapper;
    private final GatewayPropertiesBridge properties;
    private final GatewayMetrics metrics;
    private final BlockingIoScheduler blockingIo;
    private final TransactionTemplate transactions;

    public ReservationRecoveryService(
            BudgetReservationMapper reservationMapper,
            GatewayRequestMapper requestMapper,
            GatewayPropertiesBridge properties,
            GatewayMetrics metrics,
            BlockingIoScheduler blockingIo,
            PlatformTransactionManager transactionManager) {
        this.reservationMapper = reservationMapper;
        this.requestMapper = requestMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.blockingIo = blockingIo;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${aicostops.gateway.reservation-recovery-interval-ms:60000}")
    public void recoverExpired() {
        blockingIo.run(this::recoverExpiredBlocking).subscribe();
    }

    void recoverExpiredBlocking() {
        List<ExpiredHold> holds;
        try {
            holds = reservationMapper.findExpiredActiveHolds(
                    properties.reservationRecoveryBatchSize());
        } catch (RuntimeException ex) {
            log.warn("Reservation recovery scan failed", ex);
            metrics.recordReservationRecovery("FAILED");
            return;
        }
        for (var hold : holds) {
            recoverOne(hold);
        }
    }

    private void recoverOne(ExpiredHold hold) {
        try {
            // Each hold converges in its own short transaction so one slow
            // Budget lock never blocks the rest of the batch.
            var outcome = transactions.execute(status -> recoverOneBlocking(hold));
            metrics.recordReservationRecovery(outcome == null ? "SKIPPED" : outcome);
        } catch (RuntimeException ex) {
            log.warn("Reservation recovery failed for reservation {}", hold.reservationId(), ex);
            metrics.recordReservationRecovery("FAILED");
        }
    }

    String recoverOneBlocking(ExpiredHold hold) {
        // Lock order: BillingPeriod -> Budget -> Reservation.
        var periodStatus = requestMapper.lockBillingPeriod(hold.billingPeriodId(), hold.orgId());
        if (periodStatus == null) {
            return "SKIPPED";
        }
        var budget = reservationMapper.lockBudgetById(hold.orgId(), hold.budgetId());
        if (budget == null) {
            return "SKIPPED";
        }
        var reservation = reservationMapper.lockReservationById(hold.orgId(), hold.reservationId());
        if (reservation == null || !"ACTIVE".equals(reservation.status())) {
            return "SKIPPED";
        }
        if (reservation.version() != hold.version()) {
            return "SKIPPED";
        }

        var request = requestMapper.findById(reservation.requestId(), hold.orgId());
        var attempt = requestMapper.findAttemptById(reservation.routeAttemptId(), hold.orgId());
        if (request == null || attempt == null) {
            return "SKIPPED";
        }

        if (isDefinitivelyPreDispatch(request.state(), attempt.status())) {
            if (reservationMapper.releaseActiveReservation(
                    reservation.id(), hold.orgId(), reservation.version()) != 1) {
                return "SKIPPED";
            }
            reservationMapper.markRequestFailedPreDispatch(reservation.requestId(), hold.orgId());
            return "RELEASED";
        }

        // Dispatch may have happened: hold the money conservatively.
        if (reservationMapper.holdActiveReservation(
                reservation.id(), hold.orgId(), reservation.version()) != 1) {
            return "SKIPPED";
        }
        return "PENDING_HOLD";
    }

    private static boolean isDefinitivelyPreDispatch(String requestState, String attemptStatus) {
        return ("RESERVED".equals(requestState) || "VALIDATED".equals(requestState))
                && "PLANNED".equals(attemptStatus);
    }

    public record ExpiredHold(
            long orgId,
            long reservationId,
            long version,
            long budgetId,
            long billingPeriodId) {
    }
}
