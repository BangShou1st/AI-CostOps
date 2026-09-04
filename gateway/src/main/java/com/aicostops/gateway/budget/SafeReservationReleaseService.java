package com.aicostops.gateway.budget;

import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Releases an old hold only after durable SAFE evidence and exact lock order. */
@Service
public class SafeReservationReleaseService {

    private final GatewayRequestMapper requestMapper;
    private final BudgetReservationMapper reservationMapper;
    private final TransactionTemplate transactions;

    public SafeReservationReleaseService(GatewayRequestMapper requestMapper,
            BudgetReservationMapper reservationMapper, PlatformTransactionManager manager) {
        this.requestMapper = requestMapper;
        this.reservationMapper = reservationMapper;
        this.transactions = new TransactionTemplate(manager);
    }

    public ReleaseResult releaseForSafeAttempt(long orgId, long requestId, long attemptId, long billingPeriodId) {
        var result = transactions.execute(status -> {
            var periodStatus = requestMapper.lockBillingPeriod(billingPeriodId, orgId);
            if (!"OPEN".equals(periodStatus)) return new ReleaseResult(ReleaseStatus.SKIPPED, -1L);
            var existing = reservationMapper.findByRouteAttempt(orgId, attemptId);
            if (existing == null) return new ReleaseResult(ReleaseStatus.NONE, -1L);
            var budget = reservationMapper.lockBudgetById(orgId, existing.budgetId());
            if (budget == null) return new ReleaseResult(ReleaseStatus.SKIPPED, existing.id());
            var reservation = reservationMapper.lockReservationByRouteAttempt(orgId, attemptId);
            var attempt = requestMapper.findAttemptById(orgId, attemptId);
            if (attempt == null || !"SAFE_NO_BILLABLE_EXECUTION".equals(attempt.status())) {
                return new ReleaseResult(ReleaseStatus.SKIPPED, existing.id());
            }
            if (reservation == null) return new ReleaseResult(ReleaseStatus.NONE, existing.id());
            if ("PENDING_HOLD".equals(reservation.status())) {
                return new ReleaseResult(ReleaseStatus.PENDING_HOLD, reservation.id());
            }
            if (!"ACTIVE".equals(reservation.status())) {
                return new ReleaseResult(ReleaseStatus.NONE, reservation.id());
            }
            if (reservationMapper.releaseActiveReservation(reservation.id(), orgId, reservation.version()) != 1) {
                return new ReleaseResult(ReleaseStatus.SKIPPED, reservation.id());
            }
            return new ReleaseResult(ReleaseStatus.RELEASED, reservation.id());
        });
        return result == null ? new ReleaseResult(ReleaseStatus.SKIPPED, -1L) : result;
    }

    public enum ReleaseStatus { RELEASED, NONE, PENDING_HOLD, SKIPPED }
    public record ReleaseResult(ReleaseStatus status, long reservationId) { }
}
