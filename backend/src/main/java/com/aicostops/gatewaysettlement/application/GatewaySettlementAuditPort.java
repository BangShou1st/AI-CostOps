package com.aicostops.gatewaysettlement.application;

import java.math.BigDecimal;

/** System audit seam for settlement financial mutations. */
public interface GatewaySettlementAuditPort {

    void settlementPosted(long organizationId, long settlementId, long requestId,
            long usageFactId, long routeAttemptId, long providerAccountId,
            long providerModelId, long pricingVersionId, String financialScopeType,
            long financialScopeId, BigDecimal postedAmount, String currency,
            Long reservationId, boolean reservationOverrun);
}
