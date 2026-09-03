package com.aicostops.gateway.request;

import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable financial safety fence. {@link #commitDispatchFence} runs one
 * short MySQL transaction that locks the OPEN BillingPeriod {@code FOR
 * UPDATE}, persists {@code billing_period_id}, and moves request and route
 * attempt to {@code DISPATCH_INTENT}. Only then may the caller perform
 * potentially billable Provider I/O.
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

    public DispatchFenceService(GatewayRequestMapper requestMapper) {
        this.requestMapper = requestMapper;
    }

    @Transactional
    public void commitDispatchFence(long orgId, long requestId, long routeAttemptId, long periodId) {
        var status = requestMapper.lockBillingPeriod(periodId, orgId);
        if (status == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Billing period is unavailable");
        }
        if (!"OPEN".equals(status)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Billing period is not open for dispatch");
        }
        var updated = requestMapper.markRequestDispatchIntent(requestId, orgId, periodId);
        if (updated == 0) {
            var existing = requestMapper.findById(requestId, orgId);
            if (existing != null && IN_PROGRESS_STATES.contains(existing.state())) {
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