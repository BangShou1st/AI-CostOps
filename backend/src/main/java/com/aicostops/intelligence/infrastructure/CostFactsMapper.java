package com.aicostops.intelligence.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Derived-facts reads over effective POSTED Ledger lineage (M18 repair).
 *
 * <p>Authoritative money is {@code SUM(ledger_entry.amount)} over POSTED postings where
 * {@code source_gateway_settlement_id IS NOT NULL OR source_reconciliation_adjustment_id
 * IS NOT NULL}. Initial Gateway Settlement postings, append-only correction
 * reversal/replacement entries (same source_gateway_settlement_id, signed amounts) and
 * reconciliation-adjustment postings are all honored; history is never rewritten and no
 * second V3 financial truth exists. Currencies never mix (grouped + filtered by currency).
 * Advisor-owned spend stays in org financial totals elsewhere but is excluded from
 * trigger/candidate roots here by default (feedback-loop protection).
 * Aggregation behavior for unmappable effects: CASE_FULL reconciliation adjustments without a
 * gateway lineage are included in org/project/team/cost-center grains via their own
 * ledger_entry dimensions, but excluded from provider/logical-model grains which require an
 * exact gateway_settlement lineage (no invented provider/model inference).
 */
@Mapper
public interface CostFactsMapper {

    String ADVISOR_EXCLUSION = """
            AND (si.id IS NULL OR si.code <> 'AICOSTOPS_ADVISOR')
            """;

    String LEDGER_ADVISOR_EXCLUSION = """
            AND (si.id IS NULL OR si.code <> 'AICOSTOPS_ADVISOR')
            AND (si2.id IS NULL OR si2.code <> 'AICOSTOPS_ADVISOR')
            """;

    /**
     * Effective POSTED Ledger lineage: initial Gateway Settlement postings, append-only
     * correction reversal/replacement entries (same source_gateway_settlement_id, signed amounts),
     * reconciliation-adjustment postings, and legal DIRECT_PROVIDER_CHARGE postings via
     * source_charge_fact_id. History is never rewritten; no second V3 financial truth exists.
     */
    String LEDGER_SCOPE = """
            le.org_id=#{organizationId} AND le.currency=#{currency} AND lp.status='POSTED'
              AND lp.posted_at >= #{start} AND lp.posted_at < #{end}
              AND (le.source_gateway_settlement_id IS NOT NULL
                OR le.source_reconciliation_adjustment_id IS NOT NULL
                OR le.source_charge_fact_id IS NOT NULL)
            """;

    String LEDGER_JOINS = """
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            LEFT JOIN gateway_settlement gs ON gs.id=le.source_gateway_settlement_id AND gs.org_id=le.org_id
            LEFT JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            LEFT JOIN reconciliation_adjustment ra ON ra.id=le.source_reconciliation_adjustment_id AND ra.org_id=le.org_id
            LEFT JOIN gateway_request gr2 ON gr2.id=ra.gateway_request_id AND gr2.org_id=ra.org_id
            LEFT JOIN service_identity si2 ON si2.id=gr2.service_identity_id AND si2.org_id=gr2.org_id
            """;

    @Select("""
            SELECT DATE(lp.posted_at) AS day,SUM(le.amount) AS amount
            """ + LEDGER_JOINS + "WHERE " + LEDGER_SCOPE + LEDGER_ADVISOR_EXCLUSION + """
            GROUP BY DATE(lp.posted_at) ORDER BY day
            """)
    List<DailyTotal> orgDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(lp.posted_at) AS day,le.project_id AS scopeId,
              p.code AS scopeLabel,SUM(le.amount) AS amount
            """ + LEDGER_JOINS + """
            LEFT JOIN project p ON p.id=le.project_id AND p.org_id=le.org_id
            """ + " WHERE " + LEDGER_SCOPE + LEDGER_ADVISOR_EXCLUSION + """
              AND le.project_id IS NOT NULL
            GROUP BY DATE(lp.posted_at),le.project_id,p.code ORDER BY scopeId,day
            """)
    List<ScopedDailyTotal> projectDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(lp.posted_at) AS day,le.team_id AS scopeId,
              t.code AS scopeLabel,SUM(le.amount) AS amount
            """ + LEDGER_JOINS + """
            LEFT JOIN team t ON t.id=le.team_id AND t.org_id=le.org_id
            """ + " WHERE " + LEDGER_SCOPE + LEDGER_ADVISOR_EXCLUSION + """
              AND le.team_id IS NOT NULL
            GROUP BY DATE(lp.posted_at),le.team_id,t.code ORDER BY scopeId,day
            """)
    List<ScopedDailyTotal> teamDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(lp.posted_at) AS day,le.cost_center_id AS scopeId,
              c.code AS scopeLabel,SUM(le.amount) AS amount
            """ + LEDGER_JOINS + """
            LEFT JOIN cost_center c ON c.id=le.cost_center_id AND c.org_id=le.org_id
            """ + " WHERE " + LEDGER_SCOPE + LEDGER_ADVISOR_EXCLUSION + """
              AND le.cost_center_id IS NOT NULL
            GROUP BY DATE(lp.posted_at),le.cost_center_id,c.code ORDER BY scopeId,day
            """)
    List<ScopedDailyTotal> costCenterDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * Provider grain: settlement-linked entries attribute through their settlement, plus direct
     * Provider Charge entries attributed through the deterministic confirmed import lineage
     * (charge_fact -&gt; raw_provider_record -&gt; import_attempt -&gt; import_batch.provider_account_id).
     * Charge entries without that lineage are excluded here (never invented) but remain visible
     * in the organization/project/team/cost-center totals through their own ledger dimensions.
     */
    @Select("""
            SELECT day,scopeId,scopeLabel,SUM(amount) AS amount FROM (
            SELECT DATE(lp.posted_at) AS day,gs.provider_account_id AS scopeId,
              pa.display_name AS scopeLabel,SUM(le.amount) AS amount
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            JOIN gateway_settlement gs ON gs.id=le.source_gateway_settlement_id AND gs.org_id=le.org_id
            LEFT JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            LEFT JOIN provider_account pa ON pa.id=gs.provider_account_id
            WHERE le.org_id=#{organizationId} AND le.currency=#{currency} AND lp.status='POSTED'
              AND lp.posted_at >= #{start} AND lp.posted_at < #{end}
              AND le.source_gateway_settlement_id IS NOT NULL
              AND (si.id IS NULL OR si.code <> 'AICOSTOPS_ADVISOR')
            GROUP BY DATE(lp.posted_at),gs.provider_account_id,pa.display_name
            UNION ALL
            SELECT DATE(lp.posted_at) AS day,ib.provider_account_id AS scopeId,
              pa.display_name AS scopeLabel,SUM(le.amount) AS amount
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            JOIN charge_fact cf ON cf.id=le.source_charge_fact_id AND cf.org_id=le.org_id
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=le.org_id
            LEFT JOIN provider_account pa ON pa.id=ib.provider_account_id
            WHERE le.org_id=#{organizationId} AND le.currency=#{currency} AND lp.status='POSTED'
              AND lp.posted_at >= #{start} AND lp.posted_at < #{end}
              AND le.source_charge_fact_id IS NOT NULL
            GROUP BY DATE(lp.posted_at),ib.provider_account_id,pa.display_name) grains
            GROUP BY day,scopeId,scopeLabel ORDER BY scopeId,day
            """)
    List<ScopedDailyTotal> providerDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT DATE(lp.posted_at) AS day,mc.model_key AS scopeLabel,SUM(le.amount) AS amount
            FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            JOIN gateway_settlement gs ON gs.id=le.source_gateway_settlement_id AND gs.org_id=le.org_id
            JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
            LEFT JOIN service_identity si ON si.id=gr.service_identity_id AND si.org_id=gr.org_id
            JOIN provider_model pm ON pm.id=gs.provider_model_id
            JOIN model_catalog mc ON mc.id=pm.model_id
            WHERE le.org_id=#{organizationId} AND le.currency=#{currency} AND lp.status='POSTED'
              AND lp.posted_at >= #{start} AND lp.posted_at < #{end}
              AND le.source_gateway_settlement_id IS NOT NULL
              AND (si.id IS NULL OR si.code <> 'AICOSTOPS_ADVISOR')
            GROUP BY DATE(lp.posted_at),mc.model_key ORDER BY scopeLabel,day
            """)
    List<LabeledDailyTotal> modelDaily(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("""
            SELECT gud.dimension_code AS dimensionCode,SUM(gud.quantity) AS quantity
            FROM gateway_usage_dimension gud
            JOIN gateway_usage_fact guf ON guf.id=gud.usage_fact_id AND guf.org_id=gud.org_id
            JOIN gateway_settlement gs ON gs.usage_fact_id=guf.id AND gs.org_id=guf.org_id
            LEFT JOIN gateway_request gr ON gr.id=gs.request_id AND gr.org_id=gs.org_id
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
            JOIN model_catalog mc ON mc.id=pm.model_id
            JOIN provider_account pa ON pa.id=pv.provider_account_id AND pa.org_id=pv.org_id
            JOIN provider_connection_profile pcp ON pcp.org_id=pv.org_id
              AND pcp.provider_account_id=pv.provider_account_id AND pcp.status='ACTIVE'
            WHERE pv.org_id=#{organizationId} AND pv.currency=#{currency} AND pv.status='ACTIVE'
              AND pv.effective_from <= #{now} AND (pv.effective_to IS NULL OR pv.effective_to > #{now})
              AND pm.model_id=#{logicalModelId} AND pm.status='ACTIVE' AND pm.routing_eligible=TRUE
              AND pa.status='ACTIVE' AND pa.provider_code=pm.provider_code
              AND (pm.owner_org_id IS NULL OR pm.owner_org_id=#{organizationId})
              AND (mc.owner_org_id IS NULL OR mc.owner_org_id=#{organizationId})
              AND (pm.provider_account_id IS NULL OR pm.provider_account_id=pv.provider_account_id)
              AND (pcp.auth_type='NONE' OR EXISTS(SELECT 1 FROM provider_credential pcred
                WHERE pcred.org_id=pv.org_id AND pcred.provider_account_id=pv.provider_account_id
                AND pcred.status='ACTIVE'))
              AND EXISTS(SELECT 1 FROM pricing_rate pr
                WHERE pr.org_id=pv.org_id AND pr.pricing_version_id=pv.id)
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

    @Select("""
            SELECT DISTINCT currency FROM gateway_settlement
            WHERE org_id=#{organizationId} AND status='SETTLED'
            UNION
            SELECT DISTINCT le.currency FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            WHERE le.org_id=#{organizationId} AND lp.status='POSTED'
              AND (le.source_gateway_settlement_id IS NOT NULL
                OR le.source_reconciliation_adjustment_id IS NOT NULL
                OR le.source_charge_fact_id IS NOT NULL)
            """)
    List<String> settledCurrencies(@Param("organizationId") long organizationId);

    @Select("""
            SELECT DISTINCT pm.model_id
            FROM pricing_version pv JOIN provider_model pm ON pm.id=pv.provider_model_id
            WHERE pv.org_id=#{organizationId} AND pv.currency=#{currency} AND pv.status='ACTIVE'
              AND pv.effective_from <= #{now} AND (pv.effective_to IS NULL OR pv.effective_to > #{now})
            """)
    List<Long> distinctPricedLogicalModels(@Param("organizationId") long organizationId,
            @Param("currency") String currency, @Param("now") java.time.Instant now);

    @Select("""
            SELECT DISTINCT org_id FROM gateway_settlement
            WHERE settled_at >= #{since} AND status='SETTLED'
            UNION
            SELECT DISTINCT le.org_id FROM ledger_entry le
            JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
            WHERE lp.posted_at >= #{since} AND lp.status='POSTED'
              AND (le.source_gateway_settlement_id IS NOT NULL
                OR le.source_reconciliation_adjustment_id IS NOT NULL
                OR le.source_charge_fact_id IS NOT NULL)
            LIMIT #{limit}
            """)
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

    /**
     * Whether a candidate is already referenced by an ACTIVE routing revision. Savings admission
     * no longer requires this (a non-routed but otherwise production-ready candidate is emitted
     * with routing-change-required semantics); APPLIED still requires a human-created ACTIVE link.
     */
    @Select("""
            SELECT COUNT(*) FROM routing_policy_candidate rpc
            JOIN routing_policy rp ON rp.id=rpc.routing_policy_id AND rp.org_id=rpc.org_id
            WHERE rpc.org_id=#{organizationId} AND rpc.provider_account_id=#{accountId}
              AND rpc.provider_model_id=#{modelId} AND rpc.status='ACTIVE'
              AND rp.status='ACTIVE' AND rp.model_id=#{logicalModelId}
            """)
    int countActiveRoutingReferences(@Param("organizationId") long organizationId,
            @Param("accountId") long accountId, @Param("modelId") long modelId,
            @Param("logicalModelId") long logicalModelId);

    @Select("SELECT id,org_id,model_id,status FROM routing_policy WHERE id=#{policyId} AND org_id=#{organizationId}")
    RoutingPolicyRow findRoutingPolicy(@Param("policyId") long policyId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT rpc.provider_account_id AS accountId,rpc.provider_model_id AS modelId,rpc.status AS status
            FROM routing_policy_candidate rpc
            WHERE rpc.org_id=#{organizationId} AND rpc.routing_policy_id=#{policyId}
              AND rpc.provider_account_id=#{accountId} AND rpc.provider_model_id=#{modelId}
            """)
    RoutingCandidateRow findRoutingCandidate(@Param("organizationId") long organizationId,
            @Param("policyId") long policyId, @Param("accountId") long accountId,
            @Param("modelId") long modelId);

    record BudgetRow(long id, String scopeType, long scopeId, String currency,
            BigDecimal totalAmount, BigDecimal actualAmount, BigDecimal committedAmount) {
    }

    record RoutingPolicyRow(long id, long orgId, long modelId, String status) {
    }

    record RoutingCandidateRow(long accountId, long modelId, String status) {
    }
}
