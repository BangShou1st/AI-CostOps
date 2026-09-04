package com.aicostops.reconciliation.application.blockers;

import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.infrastructure.GatewayCloseBlockerMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Gateway financial-work Close blocker. Post-dispatch requests block when
 * financial truth is absent or non-terminal: no current usage, INCOMPLETE or
 * UNKNOWN usage, or a current FINAL usage without a SETTLED Settlement. A
 * current FINAL usage with a SETTLED Settlement is terminal even if transport
 * ended in a failure state. ACTIVE/PENDING_HOLD reservations remain blockers;
 * RELEASED/FINALIZED holds do not block on their own. The shared
 * BillingPeriod lock serializes this scan with financial mutation and Close.
 */
@Component
public final class GatewayFinancialWorkBlockerProvider implements CloseBlockerProvider {

    private final GatewayCloseBlockerMapper gatewayCloseBlockerMapper;

    public GatewayFinancialWorkBlockerProvider(GatewayCloseBlockerMapper gatewayCloseBlockerMapper) {
        this.gatewayCloseBlockerMapper = gatewayCloseBlockerMapper;
    }

    @Override
    public CloseBlockerCode code() {
        return CloseBlockerCode.PENDING_GATEWAY_FINANCIAL_WORK;
    }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var unresolvedRequests = gatewayCloseBlockerMapper.countUnresolvedFinancialWork(
                context.organizationId(), context.billingPeriodId());
        var unresolvedReservations = gatewayCloseBlockerMapper.countUnresolvedReservations(
                context.organizationId(), context.billingPeriodId());
        var total = unresolvedRequests + unresolvedReservations;
        var summary = Map.<String, Object>of(
                "blockedStates",
                "post-dispatch with no current usage, INCOMPLETE/UNKNOWN usage, "
                        + "or current FINAL without SETTLED Settlement",
                "blockedReservationStates", "ACTIVE, PENDING_HOLD",
                "unresolvedRequests", unresolvedRequests,
                "unresolvedReservations", unresolvedReservations,
                "billingPeriodId", context.billingPeriodId());
        return total == 0
                ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), total, summary);
    }
}
