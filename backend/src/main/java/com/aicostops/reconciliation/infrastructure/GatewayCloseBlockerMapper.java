package com.aicostops.reconciliation.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read projection over Gateway-owned durable facts used by the reconciliation
 * Close blockers. M12 extends the M11 conservative rule with ACTIVE and
 * PENDING_HOLD budget reservations: held money blocks normal Close.
 * RELEASED/FINALIZED holds never block on their own. Route history is
 * evaluated only for unresolved possible-billable work, so SAFE history and
 * a completed/settled successor do not create a false blocker.
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
              AND (
                NOT EXISTS (
                  SELECT 1 FROM gateway_route_attempt ra_any
                  WHERE ra_any.org_id=gr.org_id AND ra_any.request_id=gr.id)
                OR EXISTS (
                  SELECT 1
                  FROM gateway_route_attempt ra_live
                  LEFT JOIN gateway_usage_fact uf_live
                    ON uf_live.org_id=ra_live.org_id
                   AND uf_live.route_attempt_id=ra_live.id
                   AND uf_live.status='FINAL'
                   LEFT JOIN gateway_settlement gs_live
                     ON gs_live.org_id=ra_live.org_id
                    AND gs_live.route_attempt_id=ra_live.id
                    AND gs_live.usage_fact_id=uf_live.id
                   WHERE ra_live.org_id=gr.org_id AND ra_live.request_id=gr.id
                     AND (
                       ra_live.status IN ('PLANNED','DISPATCH_INTENT','BILLABLE_POSSIBLE','COMPLETED')
                       AND (uf_live.id IS NULL OR gs_live.id IS NULL
                            OR gs_live.status <> 'SETTLED')
                     )
                 )
               )
              -- M15: a valid immutable gateway_financial_resolution is reviewed
              -- terminal financial truth for exactly this request. It is valid
              -- only when no still-effective reservation contradicts the
              -- recorded reservation outcome.
              AND NOT EXISTS (
                SELECT 1
                FROM gateway_financial_resolution gfr
                LEFT JOIN budget_reservation br_res
                  ON br_res.id=gfr.reservation_id AND br_res.org_id=gfr.org_id
                WHERE gfr.org_id=gr.org_id AND gfr.request_id=gr.id
                  AND (
                    gfr.reservation_id IS NULL
                    OR (gfr.reservation_outcome='FINALIZED' AND br_res.status='FINALIZED')
                    OR (gfr.reservation_outcome='RELEASED' AND br_res.status='RELEASED')
                  ))
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
