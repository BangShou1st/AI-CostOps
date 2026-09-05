package com.aicostops.gateway.request;

import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable financial safety fence (TX2).
 *
 * <p>{@link #commitDispatchFence} runs one short MySQL transaction that locks
 * the OPEN BillingPeriod {@code FOR UPDATE}, verifies the M12 budget admission
 * (a budget-controlled RESERVED request must have a matching route attempt, a
 * matching ACTIVE reservation and a matching BillingPeriod; an explicitly
 * allowed unbudgeted OPTIONAL request may proceed), persists
 * {@code billing_period_id}, and moves request and route attempt to
 * {@code DISPATCH_INTENT}. Only then may the caller perform potentially
 * billable Provider I/O.
 *
 * <p>Close and dispatch serialize on the same BillingPeriod lock. If Close
 * wins, the fence rejects before any Provider I/O; if dispatch wins, the
 * request remains possible-billable recovery evidence that blocks Close.
 */
@Service
public class DispatchFenceService {

    private static final Set<String> IN_PROGRESS_STATES = Set.of(
            "DISPATCH_INTENT", "UPSTREAM_ACTIVE");

    private final GatewayRequestMapper requestMapper;
    private final BudgetReservationMapper reservationMapper;

    public DispatchFenceService(
            GatewayRequestMapper requestMapper, BudgetReservationMapper reservationMapper) {
        this.requestMapper = requestMapper;
        this.reservationMapper = reservationMapper;
    }

    @Transactional
    public void commitDispatchFence(
            long orgId, long requestId, long routeAttemptId, long periodId,
            AdmissionResult admission) {
        var status = requestMapper.lockBillingPeriod(periodId, orgId);
        if (status == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Billing period is unavailable");
        }
        if (!"OPEN".equals(status)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Billing period is not open for dispatch");
        }
        if (admission == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Budget admission is required before dispatch");
        }
        if (admission.outcome() == AdmissionOutcome.RESERVED) {
            // Budget-controlled: the request must carry the matching ACTIVE
            // reservation on the matching attempt and period. A recovery that
            // released the hold concurrently fails this fence instead of
            // dispatching unbudgeted.
            var reservation = reservationMapper.findByRouteAttempt(orgId, routeAttemptId);
            if (reservation == null
                    || reservation.id() != admission.reservationId()
                    || !"ACTIVE".equals(reservation.status())
                    || reservation.budgetId() != admission.budgetId()
                    || reservation.billingPeriodId() != periodId
                    || reservation.routeAttemptId() != routeAttemptId) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "No matching active reservation exists for dispatch");
            }
        }
        var existing = requestMapper.findById(requestId, orgId);
        if (existing == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Request cannot be found for dispatch");
        }
        var routeAttempt = requestMapper.findAttemptById(orgId, routeAttemptId);
        if (routeAttempt == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Route attempt cannot be found for dispatch");
        }
        // A concurrent replay may converge on the same attempt after the
        // winner already crossed TX2. It must observe the durable in-progress
        // identity, not turn the second mark into a generic dependency error.
        if (!"PLANNED".equals(routeAttempt.status())) {
            if (!IN_PROGRESS_STATES.contains(existing.state())) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "Route attempt cannot reach dispatch intent");
            }
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS,
                    "The same idempotency identity is already being dispatched");
        }
        boolean firstAttempt = existing.state().equals("VALIDATED") || existing.state().equals("RESERVED");
        if (!firstAttempt) {
            if (!Set.of("DISPATCH_INTENT", "UPSTREAM_ACTIVE").contains(existing.state())
                    || existing.billingPeriodId() == null || existing.billingPeriodId() != periodId) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS,
                        "The request is not eligible for a later dispatch attempt");
            }
        }
        var updated = firstAttempt ? requestMapper.markRequestDispatchIntent(requestId, orgId, periodId) : 1;
        if (updated == 0) {
            var current = requestMapper.findById(requestId, orgId);
            if (current != null && IN_PROGRESS_STATES.contains(current.state())) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS,
                        "The same idempotency identity is already being dispatched");
            }
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Request cannot reach dispatch intent");
        }
        if (updated != 1) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Request cannot reach dispatch intent");
        }
        var attemptUpdated = requestMapper.markRouteAttemptDispatchIntent(routeAttemptId, orgId);
        if (attemptUpdated != 1) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Route attempt cannot reach dispatch intent");
        }
    }
}
