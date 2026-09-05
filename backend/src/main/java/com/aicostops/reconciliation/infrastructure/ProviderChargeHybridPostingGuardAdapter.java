package com.aicostops.reconciliation.infrastructure;

import com.aicostops.ledger.application.ProviderChargeHybridPostingGuard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Component;

/**
 * Reconciliation-owned implementation of the Ledger posting fence. The hybrid
 * overlap probe is deliberately conservative: the same organization, provider
 * account, currency and BillingPeriod with any durable non-PLANNED,
 * non-SAFE possible-billable route attempt counts as overlap. A conservative
 * false block is acceptable and retriable; a false allow that double-counts
 * cost is not.
 */
@Component
public class ProviderChargeHybridPostingGuardAdapter implements ProviderChargeHybridPostingGuard {

    private final ProviderChargeHybridGuardMapper mapper;

    public ProviderChargeHybridPostingGuardAdapter(
            ProviderChargeHybridGuardMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public HybridPostingDecision checkHybridPostingEligibility(
            long organizationId, long chargeFactId, long billingPeriodId, String currency) {
        var disposition = mapper.selectDisposition(organizationId, chargeFactId);
        if (disposition != null) {
            return switch (disposition) {
                case "DIRECT_PROVIDER_CHARGE" ->
                        new HybridPostingDecision(
                                HybridPostingOutcome.ALLOWED_DIRECT_DISPOSITION);
                default -> new HybridPostingDecision(
                                HybridPostingOutcome.BLOCKED_RECONCILIATION_EVIDENCE);
            };
        }
        // Import-lineage provider account of the Charge; absent lineage means
        // the Charge cannot overlap any Gateway provider account.
        var providerAccountId = mapper.selectChargeProviderAccountId(
                organizationId, chargeFactId);
        if (providerAccountId == null) {
            return new HybridPostingDecision(HybridPostingOutcome.ALLOWED);
        }
        var overlap = mapper.countHybridOverlap(organizationId, providerAccountId,
                currency, billingPeriodId);
        return new HybridPostingDecision(overlap > 0
                ? HybridPostingOutcome.BLOCKED_HYBRID_OVERLAP
                : HybridPostingOutcome.ALLOWED);
    }

    @Mapper
    public interface ProviderChargeHybridGuardMapper {

        @Select("""
                SELECT disposition FROM provider_charge_disposition
                WHERE org_id=#{organizationId} AND charge_fact_id=#{chargeFactId}
                """)
        String selectDisposition(
                @Param("organizationId") long organizationId,
                @Param("chargeFactId") long chargeFactId);

        @Select("""
                SELECT ib.provider_account_id
                FROM charge_fact cf
                JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
                JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
                JOIN import_batch ib
                  ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
                WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
                  AND ib.provider_account_id IS NOT NULL
                """)
        Long selectChargeProviderAccountId(
                @Param("organizationId") long organizationId,
                @Param("chargeFactId") long chargeFactId);

        @Select("""
                SELECT COUNT(*)
                FROM gateway_route_attempt ra
                JOIN gateway_request gr
                  ON gr.id=ra.request_id AND gr.org_id=ra.org_id
                JOIN pricing_version pv
                  ON pv.id=ra.pricing_version_id
                WHERE ra.org_id=#{organizationId}
                  AND ra.provider_account_id=#{providerAccountId}
                  AND pv.currency=#{currency}
                  AND ra.status IN ('DISPATCH_INTENT','BILLABLE_POSSIBLE','COMPLETED')
                  AND gr.billing_period_id=#{billingPeriodId}
                """)
        long countHybridOverlap(
                @Param("organizationId") long organizationId,
                @Param("providerAccountId") long providerAccountId,
                @Param("currency") String currency,
                @Param("billingPeriodId") long billingPeriodId);
    }
}
