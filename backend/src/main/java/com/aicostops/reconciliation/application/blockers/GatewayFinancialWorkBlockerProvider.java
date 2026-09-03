package com.aicostops.reconciliation.application.blockers;

import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.infrastructure.GatewayCloseBlockerMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * M11 Gateway Close blocker. M13 Settlement does not exist yet, so any
 * possible-billable unresolved request (at or after DISPATCH_INTENT, or
 * transport-completed without a durable Settlement) blocks normal Close.
 * The shared BillingPeriod lock in {@code DispatchFenceService} serializes
 * this scan against new dispatch fences.
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
        var count = gatewayCloseBlockerMapper.countUnresolvedFinancialWork(
                context.organizationId(), context.billingPeriodId());
        var summary = Map.<String, Object>of(
                "blockedStates",
                "DISPATCH_INTENT, UPSTREAM_ACTIVE, TRANSPORT_COMPLETED, "
                        + "CANCELED_AFTER_DISPATCH, TIMED_OUT_AFTER_DISPATCH, FAILED_AFTER_DISPATCH",
                "billingPeriodId", context.billingPeriodId());
        return count == 0
                ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), count, summary);
    }
}