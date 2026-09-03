package com.aicostops.reconciliation.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read projection over Gateway-owned durable facts used by the reconciliation
 * Close blockers. M11 is intentionally conservative: M13 Settlement does not
 * exist yet, so any possible-billable unresolved request blocks Close.
 */
@Mapper
public interface GatewayCloseBlockerMapper {

    @Select("""
            SELECT COUNT(*)
            FROM gateway_request gr
            WHERE gr.org_id=#{orgId} AND gr.billing_period_id=#{billingPeriodId}
              AND gr.state IN (
                'DISPATCH_INTENT','UPSTREAM_ACTIVE','TRANSPORT_COMPLETED',
                'CANCELED_AFTER_DISPATCH','TIMED_OUT_AFTER_DISPATCH','FAILED_AFTER_DISPATCH')
            """)
    long countUnresolvedFinancialWork(
            @Param("orgId") long orgId, @Param("billingPeriodId") long billingPeriodId);
}