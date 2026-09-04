package com.aicostops.gatewaysettlement.infrastructure;

import com.aicostops.gatewaysettlement.domain.GatewaySettlement;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** DB-backed settlement discovery and idempotent state persistence. */
@Mapper
public interface GatewaySettlementMapper {

    String SETTLEMENT_COLUMNS = """
            gs.id,gs.org_id,gs.settlement_key,gs.request_id,gs.route_attempt_id,gs.usage_fact_id,
            gs.reservation_id,gs.billing_period_id,gs.financial_scope_type,gs.financial_scope_id,
            gs.provider_account_id,gs.provider_model_id,gs.pricing_version_id,gs.currency,
            gs.calculated_amount_raw,gs.posted_amount,gs.rounding_delta,gs.status,
            gs.attempt_count,gs.next_attempt_at,gs.last_error_code,gs.ledger_posting_id,
            gs.created_at,gs.settled_at,gs.reconciliation_required_at,gs.updated_at
            """;

    @Select("""
            SELECT
            gr.id AS request_id,gr.public_request_id,gr.current_route_attempt_id,
            gr.billing_period_id,gr.financial_scope_type,gr.financial_scope_id,
            gr.org_id,ra.id AS route_attempt_id,uf.id AS usage_fact_id,
            br.id AS reservation_id,ra.provider_account_id,ra.provider_model_id,
            ra.pricing_version_id,uf.currency
            FROM gateway_request gr
            JOIN gateway_usage_fact uf
              ON uf.id=gr.current_usage_fact_id AND uf.org_id=gr.org_id
            JOIN gateway_route_attempt ra
              ON ra.id=uf.route_attempt_id AND ra.org_id=uf.org_id
            LEFT JOIN budget_reservation br
              ON br.route_attempt_id=ra.id AND br.org_id=ra.org_id
            LEFT JOIN gateway_settlement gs
              ON gs.request_id=gr.id AND gs.org_id=gr.org_id
            WHERE gr.org_id=#{organizationId}
              AND uf.status='FINAL'
              AND gs.id IS NULL
            ORDER BY gr.id ASC,uf.id ASC
            LIMIT #{limit}
            """)
    List<SettlementCandidate> selectEligibleFinalCandidates(
            @Param("organizationId") long organizationId, @Param("limit") int limit);

    @Select("""
            SELECT gs.id
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId}
              AND (gs.status='PENDING'
                OR (gs.status='RETRYABLE_FAILED'
                    AND (gs.next_attempt_at IS NULL OR gs.next_attempt_at <= #{now})))
            ORDER BY gs.id ASC
            LIMIT #{limit}
            """)
    List<Long> selectWorkIds(
            @Param("organizationId") long organizationId,
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Insert("""
            INSERT INTO gateway_settlement(
              org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
              billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
              provider_model_id,pricing_version_id,currency,status,attempt_count,next_attempt_at,
              last_error_code,ledger_posting_id,created_at,settled_at,reconciliation_required_at,updated_at)
            VALUES (#{candidate.organizationId},#{settlementKey},#{candidate.requestId},
              #{candidate.routeAttemptId},#{candidate.usageFactId},#{candidate.reservationId},
              #{candidate.billingPeriodId},#{candidate.financialScopeType},#{candidate.financialScopeId},
              #{candidate.providerAccountId},#{candidate.providerModelId},#{candidate.pricingVersionId},
              #{candidate.currency},'PENDING',0,NULL,NULL,NULL,#{now},NULL,NULL,#{now})
            """)
    int insertPending(
            @Param("candidate") SettlementCandidate candidate,
            @Param("settlementKey") String settlementKey,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + SETTLEMENT_COLUMNS + """
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId} AND gs.id=#{settlementId}
            """)
    GatewaySettlement selectById(
            @Param("organizationId") long organizationId,
            @Param("settlementId") long settlementId);

    @Select("""
            SELECT
            """ + SETTLEMENT_COLUMNS + """
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId} AND gs.id=#{settlementId}
            FOR UPDATE
            """)
    GatewaySettlement selectByIdForUpdate(
            @Param("organizationId") long organizationId,
            @Param("settlementId") long settlementId);

    @Select("""
            SELECT
            """ + SETTLEMENT_COLUMNS + """
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId} AND gs.settlement_key=#{settlementKey}
            """)
    GatewaySettlement selectByKey(
            @Param("organizationId") long organizationId,
            @Param("settlementKey") String settlementKey);

    @Select("""
            SELECT
            """ + SETTLEMENT_COLUMNS + """
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId} AND gs.request_id=#{requestId}
            """)
    GatewaySettlement selectByRequestId(
            @Param("organizationId") long organizationId,
            @Param("requestId") long requestId);

    @Select("""
            SELECT
            """ + SETTLEMENT_COLUMNS + """
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId} AND gs.usage_fact_id=#{usageFactId}
            """)
    GatewaySettlement selectByUsageFactId(
            @Param("organizationId") long organizationId,
            @Param("usageFactId") long usageFactId);

    @Update("""
            UPDATE gateway_settlement
            SET status='RETRYABLE_FAILED',attempt_count=attempt_count+1,
                next_attempt_at=#{nextAttemptAt},last_error_code=#{errorCode},updated_at=#{now}
            WHERE org_id=#{organizationId} AND id=#{settlementId}
              AND status IN ('PENDING','RETRYABLE_FAILED')
            """)
    int markRetryableFailed(
            @Param("organizationId") long organizationId,
            @Param("settlementId") long settlementId,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now);

    @Update("""
            UPDATE gateway_settlement
            SET status='RECONCILIATION_REQUIRED',reconciliation_required_at=#{now},
                next_attempt_at=NULL,last_error_code=#{errorCode},updated_at=#{now}
            WHERE org_id=#{organizationId} AND id=#{settlementId}
              AND status IN ('PENDING','RETRYABLE_FAILED')
            """)
    int markReconciliationRequired(
            @Param("organizationId") long organizationId,
            @Param("settlementId") long settlementId,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now);

    @Update("""
            UPDATE gateway_settlement
            SET calculated_amount_raw=#{calculatedAmountRaw},posted_amount=#{postedAmount},
                rounding_delta=#{roundingDelta},status='SETTLED',next_attempt_at=NULL,
                last_error_code=NULL,ledger_posting_id=#{ledgerPostingId},settled_at=#{settledAt},
                updated_at=#{settledAt}
            WHERE org_id=#{organizationId} AND id=#{settlementId}
              AND status IN ('PENDING','RETRYABLE_FAILED')
            """)
    int markSettled(
            @Param("organizationId") long organizationId,
            @Param("settlementId") long settlementId,
            @Param("calculatedAmountRaw") BigDecimal calculatedAmountRaw,
            @Param("postedAmount") BigDecimal postedAmount,
            @Param("roundingDelta") BigDecimal roundingDelta,
            @Param("ledgerPostingId") long ledgerPostingId,
            @Param("settledAt") Instant settledAt);

    record SettlementCandidate(
            long requestId,
            String publicRequestId,
            Long currentRouteAttemptId,
            long billingPeriodId,
            String financialScopeType,
            long financialScopeId,
            long organizationId,
            long routeAttemptId,
            long usageFactId,
            Long reservationId,
            long providerAccountId,
            long providerModelId,
            long pricingVersionId,
            String currency) {
    }
}
