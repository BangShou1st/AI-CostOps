package com.aicostops.reconciliation.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read projection over Gateway-owned durable facts used by the reconciliation
 * Close blockers. M12 extends the M11 conservative rule with ACTIVE and
 * PENDING_HOLD budget reservations: held money blocks normal Close.
 * RELEASED/FINALIZED holds never block on their own. A current FINAL usage is
 * financially terminal only when its Settlement is SETTLED; transport state
 * alone is not a Close blocker once the financial truth is durable.
 */
@Mapper
public interface GatewayCloseBlockerMapper {

    @Select("""
            SELECT COUNT(*)
            FROM gateway_request gr
            LEFT JOIN gateway_usage_fact uf
              ON uf.id=gr.current_usage_fact_id AND uf.org_id=gr.org_id
            LEFT JOIN gateway_settlement gs
              ON gs.request_id=gr.id AND gs.org_id=gr.org_id
            WHERE gr.org_id=#{orgId} AND gr.billing_period_id=#{billingPeriodId}
              AND gr.state IN (
                'DISPATCH_INTENT','UPSTREAM_ACTIVE','TRANSPORT_COMPLETED',
                'CANCELED_AFTER_DISPATCH','TIMED_OUT_AFTER_DISPATCH','FAILED_AFTER_DISPATCH')
              AND (
                uf.id IS NULL
                OR uf.status IN ('INCOMPLETE','UNKNOWN')
                OR (uf.status='FINAL'
                    AND (gs.id IS NULL OR gs.status IN (
                      'PENDING','RETRYABLE_FAILED','RECONCILIATION_REQUIRED')))
              )
            """)
    long countUnresolvedFinancialWork(
            @Param("orgId") long orgId, @Param("billingPeriodId") long billingPeriodId);

    @Select("""
            SELECT COUNT(*)
            FROM budget_reservation br
            JOIN gateway_request gr
              ON gr.id=br.request_id AND gr.org_id=br.org_id
            WHERE br.org_id=#{orgId} AND gr.billing_period_id=#{billingPeriodId}
              AND br.status IN ('ACTIVE','PENDING_HOLD')
            """)
    long countUnresolvedReservations(
            @Param("orgId") long orgId, @Param("billingPeriodId") long billingPeriodId);
}
