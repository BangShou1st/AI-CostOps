package com.aicostops.gatewaysettlement.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementAuditPort;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/** Secret-free system audit adapter for Gateway Settlement. */
@Component
public final class AuditGatewaySettlementAdapter implements GatewaySettlementAuditPort {

    private final AuditService audit;

    public AuditGatewaySettlementAdapter(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void settlementPosted(long organizationId, long settlementId, long requestId,
            long usageFactId, long routeAttemptId, long providerAccountId,
            long providerModelId, long pricingVersionId, String financialScopeType,
            long financialScopeId, BigDecimal postedAmount, String currency,
            Long reservationId, boolean reservationOverrun) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("settlementId", Long.toString(settlementId));
        metadata.put("requestId", Long.toString(requestId));
        metadata.put("usageFactId", Long.toString(usageFactId));
        metadata.put("routeAttemptId", Long.toString(routeAttemptId));
        metadata.put("providerAccountId", Long.toString(providerAccountId));
        metadata.put("providerModelId", Long.toString(providerModelId));
        metadata.put("pricingVersionId", Long.toString(pricingVersionId));
        metadata.put("financialScopeType", financialScopeType);
        metadata.put("financialScopeId", Long.toString(financialScopeId));
        metadata.put("postedAmount", postedAmount.toPlainString());
        metadata.put("currency", currency);
        metadata.put("reservationId", reservationId == null ? null : Long.toString(reservationId));
        metadata.put("reservationOverrun", reservationOverrun);
        // System events intentionally carry a null actor; no synthetic member
        // is created for an automated financial operation.
        audit.append("GATEWAY_SETTLEMENT_POSTED", organizationId, null,
                "GATEWAY_SETTLEMENT", settlementId, metadata);
    }
}
