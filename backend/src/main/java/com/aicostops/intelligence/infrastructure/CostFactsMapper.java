package com.aicostops.intelligence.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Derived-facts reads over settled Gateway financial truth (M18 V3).
 *
 * <p>Only SETTLED settlements feed anomaly/forecast/savings. Advisor-owned
 * spend stays in org totals but is excluded from trigger/candidate roots
 * by default (feedback-loop protection).
 */
@Mapper
public interface CostFactsMapper {

    String ADVISOR_EXCLUSION = """
            AND (si.id IS NULL OR si.code <> 'AICOSTOPS_ADVISOR')
            """;

    String SETTLED_SCOPE = """
            gs.org_id=#{organizationId} AND gs.currency=#{currency}
              AND gs.status='SETTLED' AND gs.posted_amount IS NOT NULL
              AND gs.settled_at >= #{start} AND gs.settled_at < #{end}
            """;

    @Select("""
            SELECT DATE(gs.settled_at) AS day,SUM(gs.posted_amount) AS amount
            FROM gateway_settlement gs
            JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            WHERE
            """ + SETTLED_SCOPE + ADVISOR_EXCLUSION + """
            GROUP BY DATE(gs.settled_at) ORDER BY day
            """)
    List<DailyTotal> orgDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(gs.settled_at) AS day,gs.financial_scope_id AS scopeId,SUM(gs.posted_amount) AS amount
            FROM gateway_settlement gs
            JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            WHERE
            """ + SETTLED_SCOPE + ADVISOR_EXCLUSION + """
              AND gs.financial_scope_type='PROJECT'
            GROUP BY DATE(gs.settled_at),gs.financial_scope_id ORDER BY scopeId,day
            """)
    List<ScopedDailyTotal> projectDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(gs.settled_at) AS day,gs.provider_account_id AS scopeId,
              pa.display_name AS scopeLabel,SUM(gs.posted_amount) AS amount
            FROM gateway_settlement gs
            JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            LEFT JOIN provider_account pa ON pa.id=gs.provider_account_id
            WHERE
            """ + SETTLED_SCOPE + ADVISOR_EXCLUSION + """
            GROUP BY DATE(gs.settled_at),gs.provider_account_id,pa.display_name ORDER BY scopeId,day
            """)
    List<ScopedDailyTotal> providerDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(gs.settled_at) AS day,mc.model_key AS scopeLabel,SUM(gs.posted_amount) AS amount
            FROM gateway_settlement gs
            JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            JOIN provider_model pm ON pm.id=gs.provider_model_id
            JOIN model_catalog mc ON mc.id=pm.model_id
            WHERE
            """ + SETTLED_SCOPE + ADVISOR_EXCLUSION + """
            GROUP BY DATE(gs.settled_at),mc.model_key ORDER BY scopeLabel,day
            """)
    List<LabeledDailyTotal> modelDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT gud.dimension_code AS dimensionCode,SUM(gud.quantity) AS quantity
            FROM gateway_usage_dimension gud
            JOIN gateway_usage_fact guf ON guf.id=gud.usage_fact_id AND guf.org_id=gud.org_id
            JOIN gateway_settlement gs ON gs.usage_fact_id=guf.id AND gs.org_id=guf.org_id
            JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            WHERE gs.org_id=#{organizationId} AND gs.currency=#{currency}
              AND gs.status='SETTLED'
              AND gs.settled_at >= #{start} AND gs.settled_at < #{end}
              AND gs.provider_account_id=#{accountId} AND gs.provider_model_id=#{modelId}
            """ + ADVISOR_EXCLUSION + """
            GROUP BY gud.dimension_code
            """)
    List<UsageTotal> usageTotals(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start,
            @Param("end") LocalDate end, @Param("accountId") long accountId,
            @Param("modelId") long modelId);

    @Select("""
            SELECT pv.id AS pricingVersionId,pv.provider_account_id AS accountId,
              pv.provider_model_id AS modelId,pm.model_id AS logicalModelId,
              pa.display_name AS accountLabel,pm.provider_model_name AS modelName
            FROM pricing_version pv
            JOIN provider_model pm ON pm.id=pv.provider_model_id
            JOIN provider_account pa ON pa.id=pv.provider_account_id AND pa.org_id=pv.org_id
            WHERE pv.org_id=#{organizationId} AND pv.currency=#{currency} AND pv.status='ACTIVE'
              AND pv.effective_from <= #{now} AND (pv.effective_to IS NULL OR pv.effective_to > #{now})
              AND pm.model_id=#{logicalModelId} AND pm.status='ACTIVE'
              AND pa.status='ACTIVE'
              AND EXISTS(SELECT 1 FROM provider_credential pcred
                WHERE pcred.org_id=pv.org_id AND pcred.provider_account_id=pv.provider_account_id
                AND pcred.status='ACTIVE')
            """)
    List<PricingCandidate> pricingCandidates(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("logicalModelId") long logicalModelId,
            @Param("now") java.time.Instant now);

    @Select("""
            SELECT dimension_code,unit_quantity,unit_price
            FROM pricing_rate WHERE org_id=#{organizationId} AND pricing_version_id=#{pricingVersionId}
            """)
    List<RateRow> pricingRates(@Param("organizationId") long organizationId,
            @Param("pricingVersionId") long pricingVersionId);

    @Select("""
            SELECT b.id,b.scope_type,b.scope_id,b.currency,b.total_amount,b.actual_amount,b.committed_amount
            FROM budget b JOIN billing_period bp ON bp.id=b.billing_period_id AND bp.org_id=b.org_id
            WHERE b.org_id=#{organizationId} AND b.scope_type=#{scopeType} AND b.scope_id=#{scopeId}
              AND b.currency=#{currency} AND b.status='ACTIVE' AND bp.status='OPEN'
            ORDER BY b.id DESC LIMIT 1
            """)
    BudgetRow openBudget(@Param("organizationId") long organizationId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("currency") String currency);

    @Select("""
            SELECT COALESCE(SUM(reserved_amount),0) FROM budget_reservation
            WHERE org_id=#{organizationId} AND budget_id=#{budgetId} AND status IN ('ACTIVE','PENDING_HOLD')
            """)
    BigDecimal activeReservations(@Param("organizationId") long organizationId, @Param("budgetId") long budgetId);

    @Select("SELECT DISTINCT currency FROM gateway_settlement WHERE org_id=#{organizationId} AND status='SETTLED'")
    List<String> settledCurrencies(@Param("organizationId") long organizationId);

    @Select("""
            SELECT DISTINCT pm.model_id
            FROM pricing_version pv JOIN provider_model pm ON pm.id=pv.provider_model_id
            WHERE pv.org_id=#{organizationId} AND pv.currency=#{currency} AND pv.status='ACTIVE'
              AND pv.effective_from <= #{now} AND (pv.effective_to IS NULL OR pv.effective_to > #{now})
            """)
    List<Long> distinctPricedLogicalModels(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("now") java.time.Instant now);

    @Select("SELECT DISTINCT org_id FROM gateway_settlement WHERE settled_at >= #{since} AND status='SETTLED' LIMIT #{limit}")
    List<Long> recentlyActiveOrgs(@Param("since") LocalDate since, @Param("limit") int limit);

    record DailyTotal(LocalDate day, BigDecimal amount) {
    }

    record ScopedDailyTotal(LocalDate day, long scopeId, String scopeLabel, BigDecimal amount) {
    }

    record LabeledDailyTotal(LocalDate day, String scopeLabel, BigDecimal amount) {
    }

    record UsageTotal(String dimensionCode, BigDecimal quantity) {
    }

    record PricingCandidate(long pricingVersionId, long accountId, long modelId, long logicalModelId,
            String accountLabel, String modelName) {
    }

    record RateRow(String dimensionCode, long unitQuantity, BigDecimal unitPrice) {
    }

    record BudgetRow(long id, String scopeType, long scopeId, String currency,
            BigDecimal totalAmount, BigDecimal actualAmount, BigDecimal committedAmount) {
    }
}
