package com.aicostops.reconciliation.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * M15 hybrid reconciliation persistence: exact correlation candidates,
 * run-level unresolved Gateway work and bounded evidence rows.
 */
@Mapper
public interface HybridReconciliationMapper {

    /**
     * Exact Provider request correlation candidates grouped by the persisted
     * external key and provider account. Exact correlation is only legal
     * inside one organization, one provider account and one currency: the
     * confirmed-import lineage must own the same provider account as the
     * attempt and the pricing version must belong to the same organization.
     * Provider request id equality and grouping are BINARY (case-sensitive):
     * ids that differ only by case are distinct identities and must never be
     * folded into one ambiguous group or cross-matched. Only non-PLANNED,
     * non-SAFE possible-billable current attempts of confirmed-import
     * CLEAN/SUSPECTED_DUPLICATE charges inside the half-open period
     * participate. Uniqueness filtering happens in the caller.
     */
    @Select("""
            SELECT MIN(rpr.provider_record_key) AS provider_request_id,
                   MIN(cf.id) AS charge_fact_id,
                   MIN(cf.currency) AS currency,
                   MIN(ra.id) AS route_attempt_id,
                   MIN(ra.provider_account_id) AS provider_account_id,
                   MIN(gr.id) AS request_id,
                   MIN(cf.provider_code) AS provider_code,
                   MIN(ib.source_type) AS source_type,
                   MIN(ia.parser_version) AS parser_version,
                   COUNT(DISTINCT cf.id) AS charge_count,
                   COUNT(DISTINCT gr.id) AS request_count
            FROM charge_fact cf
            JOIN raw_provider_record rpr
              ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib
              ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            JOIN gateway_route_attempt ra
              ON ra.org_id=cf.org_id
             AND ra.provider_account_id=ib.provider_account_id
             AND BINARY ra.provider_request_id = BINARY rpr.provider_record_key
             AND ra.status IN ('DISPATCH_INTENT','BILLABLE_POSSIBLE','COMPLETED')
            JOIN gateway_request gr
              ON gr.id=ra.request_id AND gr.org_id=ra.org_id
             AND gr.current_route_attempt_id=ra.id
            JOIN pricing_version pv
              ON pv.id=ra.pricing_version_id AND pv.org_id=ra.org_id
             AND pv.currency=cf.currency
            WHERE cf.org_id=#{organizationId}
              AND ib.status='CONFIRMED'
              AND ia.id=ib.confirmed_attempt_id
              AND cf.review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
              AND cf.period_start >= #{periodStart}
              AND cf.period_start < #{periodEnd}
              AND rpr.provider_record_key IS NOT NULL
            GROUP BY BINARY rpr.provider_record_key, ra.provider_account_id
            ORDER BY provider_request_id, provider_account_id
            """)
    List<ExactCorrelationGroup> selectExactCorrelationGroups(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Select("""
            SELECT cf.provider_code AS provider_code,
                   ib.source_type AS source_type,
                   ia.parser_version AS parser_version
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    ChargeImportProfile selectChargeImportProfile(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    record ChargeImportProfile(String providerCode, String sourceType, String parserVersion) {
    }

    /**
     * Run-level unresolved Gateway financial work: possible-billable requests
     * whose current financial observation is absent/INCOMPLETE/UNKNOWN or
     * whose Settlement is RECONCILIATION_REQUIRED, with no terminal M15
     * resolution. Ordinary FINAL usage without Settlement stays with normal
     * M13 settlement and is never reported here.
     */
    @Select("""
            SELECT gr.id AS request_id,
                   gr.current_route_attempt_id AS route_attempt_id,
                   gr.current_usage_fact_id AS usage_fact_id,
                   uf.status AS usage_status,
                   gs.id AS settlement_id,
                   gs.status AS settlement_status,
                   ra.provider_account_id AS provider_account_id,
                   pv.currency AS currency
            FROM gateway_request gr
            JOIN gateway_route_attempt ra
              ON ra.id=gr.current_route_attempt_id AND ra.org_id=gr.org_id
            JOIN pricing_version pv
              ON pv.id=ra.pricing_version_id
            LEFT JOIN gateway_usage_fact uf
              ON uf.id=gr.current_usage_fact_id
            LEFT JOIN gateway_settlement gs
              ON gs.request_id=gr.id AND gs.org_id=gr.org_id
            WHERE gr.org_id=#{organizationId}
              AND gr.billing_period_id=#{billingPeriodId}
              AND ra.status IN ('DISPATCH_INTENT','BILLABLE_POSSIBLE','COMPLETED')
              AND NOT EXISTS (
                SELECT 1 FROM gateway_financial_resolution gfr
                WHERE gfr.org_id=gr.org_id AND gfr.request_id=gr.id)
              AND (
                uf.id IS NULL
                OR uf.status IN ('INCOMPLETE','UNKNOWN')
                OR gs.status='RECONCILIATION_REQUIRED')
            ORDER BY gr.id
            """)
    List<UnresolvedGatewayRequest> selectUnresolvedGatewayRequests(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Insert("""
            INSERT INTO reconciliation_adjustment(
                org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                gateway_request_id,gateway_route_attempt_id,statement_charge_fact_id,
                created_by_member_id,reason_code,reason_note,created_at)
            VALUES (#{adjustment.organizationId},#{adjustment.runId},#{adjustment.caseId},
                #{adjustment.adjustmentKey},#{adjustment.adjustmentScope},
                #{adjustment.providerAccountId},#{adjustment.currency},#{adjustment.amount},
                #{adjustment.adjustmentPeriodId},#{adjustment.gatewayRequestId},
                #{adjustment.gatewayRouteAttemptId},#{adjustment.statementChargeFactId},
                #{adjustment.createdByMemberId},#{adjustment.reasonCode},#{adjustment.reasonNote},
                #{adjustment.createdAt})
            """)
    int insertAdjustment(@Param("adjustment") AdjustmentInsert adjustment);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT COUNT(*) FROM correction_group
            WHERE org_id=#{organizationId} AND id=#{correctionGroupId}
            """)
    boolean correctionGroupExists(
            @Param("organizationId") long organizationId,
            @Param("correctionGroupId") long correctionGroupId);

    @Select("""
            SELECT COUNT(*) FROM charge_fact
            WHERE org_id=#{organizationId} AND id=#{chargeFactId}
            """)
    boolean chargeExists(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT COUNT(*) FROM provider_charge_disposition
            WHERE org_id=#{organizationId} AND charge_fact_id=#{chargeFactId}
            FOR UPDATE
            """)
    long countDisposition(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Insert("""
            INSERT INTO provider_charge_disposition(
                org_id,charge_fact_id,disposition,decision_source,reconciliation_run_id,
                reconciliation_case_id,decided_by_member_id,reason_code,resolution_note,
                created_at)
            VALUES (#{disposition.organizationId},#{disposition.chargeFactId},
                #{disposition.disposition},#{disposition.decisionSource},
                #{disposition.reconciliationRunId},#{disposition.reconciliationCaseId},
                #{disposition.decidedByMemberId},#{disposition.reasonCode},
                #{disposition.reasonNote},#{disposition.createdAt})
            """)
    int insertDisposition(@Param("disposition") DispositionInsert disposition);

    record DispositionInsert(
            long organizationId,
            long chargeFactId,
            String disposition,
            String decisionSource,
            long reconciliationRunId,
            long reconciliationCaseId,
            long decidedByMemberId,
            String reasonCode,
            String reasonNote,
            Instant createdAt) {
    }

    /**
     * Bounded resolution lineage of one Gateway request: current attempt, usage,
     * settlement, reservation and frozen financial scope. Org-scoped.
     */
    @Select("""
            SELECT gr.id AS request_id,
                   gr.current_route_attempt_id AS route_attempt_id,
                   ra.status AS attempt_status,
                   ra.provider_account_id AS provider_account_id,
                   gr.financial_scope_type AS financial_scope_type,
                   gr.financial_scope_id AS financial_scope_id,
                   gr.billing_period_id AS billing_period_id,
                   gr.current_usage_fact_id AS usage_fact_id,
                   uf.status AS usage_status,
                   gs.id AS settlement_id,
                   gs.status AS settlement_status,
                   pv.currency AS currency,
                   br.id AS reservation_id,
                   br.status AS reservation_status,
                   br.version AS reservation_version,
                   br.commitment_id AS reservation_commitment_id
            FROM gateway_request gr
            JOIN gateway_route_attempt ra
              ON ra.id=gr.current_route_attempt_id AND ra.org_id=gr.org_id
            JOIN pricing_version pv ON pv.id=ra.pricing_version_id
            LEFT JOIN gateway_usage_fact uf ON uf.id=gr.current_usage_fact_id
            LEFT JOIN gateway_settlement gs
              ON gs.request_id=gr.id AND gs.org_id=gr.org_id
            LEFT JOIN budget_reservation br
              ON br.route_attempt_id=ra.id AND br.org_id=ra.org_id
            WHERE gr.org_id=#{organizationId} AND gr.id=#{requestId}
            """)
    RequestResolutionLineage selectRequestResolutionLineage(
            @Param("organizationId") long organizationId,
            @Param("requestId") long requestId);

    @Select("""
            SELECT id FROM gateway_request
            WHERE org_id=#{organizationId} AND id=#{requestId}
            FOR UPDATE
            """)
    Long lockGatewayRequest(
            @Param("organizationId") long organizationId,
            @Param("requestId") long requestId);

    /**
     * Shared financial-ownership serialization point of a statement Charge.
     * Provider Charge posting (via its charge row lock), Gateway statement
     * resolution and charge disposition decisions all acquire this same row
     * lock as the LAST financial lock in the canonical order
     * (BillingPeriod(s) → Budget(s) → Commitment → Reservation →
     * reconciliation identity → Gateway Request → Charge ownership row), so
     * no two flows can decide the financial fate of one Charge concurrently
     * and no lock-order inversion is possible.
     */
    @Select("""
            SELECT cf.id FROM charge_fact cf
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            FOR UPDATE
            """)
    Long lockChargeForFinancialOwnership(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT disposition FROM provider_charge_disposition
            WHERE org_id=#{organizationId} AND charge_fact_id=#{chargeFactId}
            """)
    String selectChargeDisposition(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT COUNT(*) FROM ledger_posting
            WHERE org_id=#{organizationId} AND source_type='PROVIDER_CHARGE'
              AND source_id=#{chargeFactId} AND status='POSTED'
            FOR UPDATE
            """)
    long countPostedProviderChargePostings(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_adjustment
            WHERE org_id=#{organizationId} AND statement_charge_fact_id=#{chargeFactId}
              AND adjustment_scope='GATEWAY_REQUEST'
            FOR UPDATE
            """)
    long countAdjustmentByStatementCharge(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    /**
     * Atomically claims the Charge as RECONCILIATION_EVIDENCE in the same
     * transaction that posts the statement adjustment. A SYSTEM_EXACT claim
     * never impersonates a member; a MANUAL claim carries the reviewer and the
     * bounded reviewed reason.
     */
    @Insert("""
            INSERT INTO provider_charge_disposition(
                org_id,charge_fact_id,disposition,decision_source,reconciliation_run_id,
                reconciliation_case_id,decided_by_member_id,reason_code,resolution_note,
                created_at)
            VALUES (#{claim.organizationId},#{claim.chargeFactId},'RECONCILIATION_EVIDENCE',
                #{claim.decisionSource},#{claim.reconciliationRunId},
                #{claim.reconciliationCaseId},#{claim.decidedByMemberId},#{claim.reasonCode},
                #{claim.reasonNote},#{claim.createdAt})
            """)
    int insertOwnershipDisposition(@Param("claim") OwnershipDispositionInsert claim);

    record OwnershipDispositionInsert(
            long organizationId,
            long chargeFactId,
            String decisionSource,
            long reconciliationRunId,
            Long reconciliationCaseId,
            Long decidedByMemberId,
            String reasonCode,
            String reasonNote,
            Instant createdAt) {
    }

    @Select("""
            SELECT disposition AS disposition, decision_source AS decision_source,
                   reconciliation_run_id AS reconciliation_run_id
            FROM provider_charge_disposition
            WHERE org_id=#{organizationId} AND charge_fact_id=#{chargeFactId}
            FOR UPDATE
            """)
    OwnershipDispositionRow selectOwnershipDisposition(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    record OwnershipDispositionRow(String disposition, String decisionSource,
            Long reconciliationRunId) {
    }

    @Insert("""
            INSERT INTO gateway_financial_resolution(
                org_id,reconciliation_run_id,reconciliation_case_id,request_id,route_attempt_id,
                usage_fact_id,gateway_settlement_id,statement_charge_fact_id,
                reconciliation_adjustment_id,reservation_id,resolution_type,reservation_outcome,
                resolved_by_member_id,reason_code,reason_note,resolved_at,created_at)
            VALUES (#{resolution.organizationId},#{resolution.runId},#{resolution.caseId},
                #{resolution.requestId},#{resolution.routeAttemptId},#{resolution.usageFactId},
                #{resolution.settlementId},#{resolution.statementChargeFactId},
                #{resolution.reconciliationAdjustmentId},#{resolution.reservationId},
                #{resolution.resolutionType},#{resolution.reservationOutcome},
                #{resolution.resolvedByMemberId},#{resolution.reasonCode},#{resolution.reasonNote},
                #{resolution.resolvedAt},#{resolution.resolvedAt})
            """)
    int insertResolution(@Param("resolution") ResolutionInsert resolution);

    @Select("""
            SELECT COUNT(*) FROM gateway_financial_resolution
            WHERE org_id=#{organizationId} AND request_id=#{requestId}
            """)
    long countResolution(
            @Param("organizationId") long organizationId,
            @Param("requestId") long requestId);

    record RequestResolutionLineage(
            long requestId,
            Long routeAttemptId,
            String attemptStatus,
            long providerAccountId,
            String financialScopeType,
            long financialScopeId,
            Long billingPeriodId,
            Long usageFactId,
            String usageStatus,
            Long settlementId,
            String settlementStatus,
            String currency,
            Long reservationId,
            String reservationStatus,
            Long reservationVersion,
            Long reservationCommitmentId) {
    }

    record ResolutionInsert(
            long organizationId,
            long runId,
            Long caseId,
            long requestId,
            long routeAttemptId,
            Long usageFactId,
            Long settlementId,
            Long statementChargeFactId,
            Long reconciliationAdjustmentId,
            Long reservationId,
            String resolutionType,
            String reservationOutcome,
            long resolvedByMemberId,
            String reasonCode,
            String reasonNote,
            Instant resolvedAt) {
    }

    @Select("""
            SELECT id,org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                   adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                   gateway_request_id,gateway_route_attempt_id,statement_charge_fact_id,
                   created_by_member_id,reason_code,reason_note,created_at
            FROM reconciliation_adjustment
            WHERE org_id=#{organizationId} AND id=#{adjustmentId}
            """)
    AdjustmentRow selectAdjustmentByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("adjustmentId") long adjustmentId);

    record AdjustmentInsert(
            long organizationId,
            long runId,
            Long caseId,
            String adjustmentKey,
            String adjustmentScope,
            long providerAccountId,
            String currency,
            BigDecimal amount,
            long adjustmentPeriodId,
            Long gatewayRequestId,
            Long gatewayRouteAttemptId,
            Long statementChargeFactId,
            long createdByMemberId,
            String reasonCode,
            String reasonNote,
            Instant createdAt) {
    }

    record AdjustmentRow(
            long id,
            long organizationId,
            long reconciliationRunId,
            Long reconciliationCaseId,
            String adjustmentKey,
            String adjustmentScope,
            long providerAccountId,
            String currency,
            BigDecimal amount,
            long adjustmentPeriodId,
            Long gatewayRequestId,
            Long gatewayRouteAttemptId,
            Long statementChargeFactId,
            long createdByMemberId,
            String reasonCode,
            String reasonNote,
            Instant createdAt) {
    }

    @Insert("""
            INSERT INTO reconciliation_evidence(
                org_id,reconciliation_run_id,reconciliation_case_id,evidence_key,
                provider_account_id,currency,match_kind,difference_kind,charge_fact_id,
                gateway_request_id,gateway_route_attempt_id,gateway_usage_fact_id,
                gateway_settlement_id,correction_group_id,reconciliation_adjustment_id,
                gateway_financial_resolution_id,ledger_posting_id,provider_request_id,
                evidence_reference,external_amount,internal_amount,difference_amount,created_at)
            VALUES (#{evidence.organizationId},#{evidence.runId},#{evidence.caseId},
                #{evidence.evidenceKey},
                #{evidence.providerAccountId},#{evidence.currency},#{evidence.matchKind},
                #{evidence.differenceKind},#{evidence.chargeFactId},
                #{evidence.gatewayRequestId},#{evidence.gatewayRouteAttemptId},
                #{evidence.gatewayUsageFactId},
                #{evidence.gatewaySettlementId},#{evidence.correctionGroupId},
                #{evidence.reconciliationAdjustmentId},#{evidence.gatewayFinancialResolutionId},
                #{evidence.ledgerPostingId},#{evidence.providerRequestId},
                #{evidence.evidenceReference},#{evidence.externalAmount},#{evidence.internalAmount},
                #{evidence.differenceAmount},#{evidence.createdAt})
            """)
    int insertEvidence(@Param("evidence") ReconciliationEvidenceRow evidence);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_evidence
            WHERE org_id=#{organizationId}
              AND reconciliation_run_id=#{runId}
              AND evidence_key=#{evidenceKey}
            """)
    long countEvidenceKey(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("evidenceKey") String evidenceKey);

    String EVIDENCE_COLUMNS = """
            re.id,re.org_id,re.reconciliation_run_id,re.reconciliation_case_id,re.evidence_key,
            re.provider_account_id,re.currency,re.match_kind,re.difference_kind,
            re.charge_fact_id,re.gateway_request_id,re.gateway_route_attempt_id,
            re.gateway_usage_fact_id,re.gateway_settlement_id,re.correction_group_id,
            re.reconciliation_adjustment_id,re.gateway_financial_resolution_id,
            re.ledger_posting_id,re.provider_request_id,re.evidence_reference,re.external_amount,
            re.internal_amount,re.difference_amount,re.created_at
            """;

    @Select("""
            <script>
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM reconciliation_evidence re
            WHERE re.org_id=#{organizationId} AND re.reconciliation_run_id=#{runId}
            <if test="matchKind != null">AND re.match_kind=#{matchKind}</if>
            <if test="gatewayRequestId != null">AND re.gateway_request_id=#{gatewayRequestId}</if>
            ORDER BY re.id ASC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<EvidenceRow> selectEvidenceByRun(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("matchKind") String matchKind,
            @Param("gatewayRequestId") Long gatewayRequestId,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reconciliation_evidence re
            WHERE re.org_id=#{organizationId} AND re.reconciliation_run_id=#{runId}
            <if test="matchKind != null">AND re.match_kind=#{matchKind}</if>
            <if test="gatewayRequestId != null">AND re.gateway_request_id=#{gatewayRequestId}</if>
            </script>
            """)
    long countEvidenceByRun(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("matchKind") String matchKind,
            @Param("gatewayRequestId") Long gatewayRequestId);

    @Select("""
            <script>
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM reconciliation_evidence re
            WHERE re.org_id=#{organizationId} AND re.reconciliation_case_id=#{caseId}
            <if test="matchKind != null">AND re.match_kind=#{matchKind}</if>
            ORDER BY re.id ASC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<EvidenceRow> selectEvidenceByCase(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId,
            @Param("matchKind") String matchKind,
            @Param("size") int size,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reconciliation_evidence re
            WHERE re.org_id=#{organizationId} AND re.reconciliation_case_id=#{caseId}
            <if test="matchKind != null">AND re.match_kind=#{matchKind}</if>
            </script>
            """)
    long countEvidenceByCase(
            @Param("organizationId") long organizationId,
            @Param("caseId") long caseId,
            @Param("matchKind") String matchKind);

    record EvidenceRow(
            long id,
            long organizationId,
            long reconciliationRunId,
            Long reconciliationCaseId,
            String evidenceKey,
            long providerAccountId,
            String currency,
            String matchKind,
            String differenceKind,
            Long chargeFactId,
            Long gatewayRequestId,
            Long gatewayRouteAttemptId,
            Long gatewayUsageFactId,
            Long gatewaySettlementId,
            Long correctionGroupId,
            Long reconciliationAdjustmentId,
            Long gatewayFinancialResolutionId,
            Long ledgerPostingId,
            String providerRequestId,
            String evidenceReference,
            BigDecimal externalAmount,
            BigDecimal internalAmount,
            BigDecimal differenceAmount,
            Instant createdAt) {
    }

    record ExactCorrelationGroup(
            String providerRequestId,
            Long chargeFactId,
            String currency,
            Long routeAttemptId,
            Long providerAccountId,
            Long requestId,
            String providerCode,
            String sourceType,
            String parserVersion,
            long chargeCount,
            long requestCount) {
    }

    record UnresolvedGatewayRequest(
            long requestId,
            Long routeAttemptId,
            Long usageFactId,
            String usageStatus,
            Long settlementId,
            String settlementStatus,
            long providerAccountId,
            String currency) {
    }

    /**
     * One bounded scope projection for a Charge against one reconciliation run:
     * confirmed-import lineage (provider account + batch confirmation),
     * canonical money fields (currency, period) and the run's BillingPeriod
     * window. Used by manual charge dispositions and manual statement bindings
     * so a server decision can prove that the Charge belongs to the case/run
     * scope in a single query instead of scattered TOCTOU-prone lookups.
     */
    @Select("""
            SELECT cf.id AS charge_fact_id,
                   cf.currency AS currency,
                   cf.period_start AS period_start,
                   cf.review_status AS review_status,
                   ib.provider_account_id AS provider_account_id,
                   ib.status AS batch_status,
                   COALESCE(ia.id = ib.confirmed_attempt_id, FALSE) AS confirmed_lineage,
                   bp.period_start AS run_period_start,
                   bp.period_end AS run_period_end
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            JOIN reconciliation_run rr ON rr.org_id=#{organizationId} AND rr.id=#{runId}
            JOIN billing_period bp ON bp.org_id=rr.org_id AND bp.id=rr.billing_period_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    ChargeScopeContext selectChargeScopeContext(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId,
            @Param("runId") long runId);

    record ChargeScopeContext(
            long chargeFactId,
            String currency,
            Instant periodStart,
            String reviewStatus,
            long providerAccountId,
            String batchStatus,
            boolean confirmedLineage,
            Instant runPeriodStart,
            Instant runPeriodEnd) {
    }

    @Select("""
            SELECT id,org_id,reconciliation_run_id,reconciliation_case_id,request_id,
                   route_attempt_id,usage_fact_id,gateway_settlement_id,statement_charge_fact_id,
                   reconciliation_adjustment_id,reservation_id,resolution_type,reservation_outcome,
                   resolved_at
            FROM gateway_financial_resolution
            WHERE org_id=#{organizationId} AND id=#{resolutionId}
            """)
    CommittedResolutionRow selectResolutionByIdAndOrganization(
            @Param("organizationId") long organizationId,
            @Param("resolutionId") long resolutionId);

    record CommittedResolutionRow(
            long id,
            long organizationId,
            long reconciliationRunId,
            Long reconciliationCaseId,
            long requestId,
            long routeAttemptId,
            Long usageFactId,
            Long gatewaySettlementId,
            Long statementChargeFactId,
            Long reconciliationAdjustmentId,
            Long reservationId,
            String resolutionType,
            String reservationOutcome,
            Instant resolvedAt) {
    }

    @Select("""
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM reconciliation_evidence re
            WHERE re.org_id=#{organizationId}
              AND re.reconciliation_run_id=#{runId}
              AND re.gateway_request_id=#{requestId}
              AND re.match_kind='EXACT_PROVIDER_REQUEST'
            ORDER BY re.id ASC
            """)
    List<EvidenceRow> selectExactEvidenceRowsForRequest(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("requestId") long requestId);

    /**
     * Bounded projection of one request's GATEWAY_UNRESOLVED evidence inside a
     * run. A resolution may only consume evidence whose route attempt,
     * provider account and currency still equal the request's current lineage:
     * anything else is stale review evidence and requires a reconciliation
     * rerun. Exact evidence carries its own lineage and is revalidated
     * separately.
     */
    @Select("""
            SELECT re.id,
                   re.match_kind AS match_kind,
                   re.reconciliation_case_id AS reconciliation_case_id,
                   re.gateway_request_id AS gateway_request_id,
                   re.gateway_route_attempt_id AS gateway_route_attempt_id,
                   re.provider_account_id AS provider_account_id,
                   re.currency AS currency,
                   re.charge_fact_id AS charge_fact_id
            FROM reconciliation_evidence re
            WHERE re.org_id=#{organizationId}
              AND re.reconciliation_run_id=#{runId}
              AND re.gateway_request_id=#{requestId}
              AND re.match_kind='GATEWAY_UNRESOLVED'
            ORDER BY re.id ASC
            """)
    List<RequestEvidenceBinding> selectUnresolvedEvidenceBindingsForRequest(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("requestId") long requestId);

    record RequestEvidenceBinding(
            long id,
            String matchKind,
            Long reconciliationCaseId,
            Long gatewayRequestId,
            Long gatewayRouteAttemptId,
            long providerAccountId,
            String currency,
            Long chargeFactId) {
    }

    @Select("""
            SELECT COUNT(*) FROM gateway_financial_resolution
            WHERE org_id=#{organizationId} AND statement_charge_fact_id=#{chargeFactId}
            FOR UPDATE
            """)
    long countResolutionByStatementCharge(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_evidence
            WHERE org_id=#{organizationId}
              AND reconciliation_run_id=#{runId}
              AND charge_fact_id=#{chargeFactId}
              AND match_kind='MANUAL_BINDING'
              AND (gateway_request_id IS NULL OR gateway_request_id <> #{requestId})
            """)
    long countConflictingManualBinding(
            @Param("organizationId") long organizationId,
            @Param("runId") long runId,
            @Param("chargeFactId") long chargeFactId,
            @Param("requestId") long requestId);

    @Select("""
            SELECT cf.amount FROM charge_fact cf
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    BigDecimal selectStatementChargeAmount(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    /**
     * Immutable Ledger amount attributable to one Gateway request through
     * preserved direct-source lineage: settlement-sourced entries (via
     * gateway_settlement.request_id) and adjustment-sourced entries (via
     * reconciliation_adjustment.gateway_request_id). Corrections contribute
     * because they preserve the direct source of the entry they correct.
     */
    @Select("""
            SELECT COALESCE(SUM(le.amount), 0) AS posted_amount
            FROM ledger_entry le
            WHERE le.org_id=#{organizationId}
              AND (
                (le.source_gateway_settlement_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM gateway_settlement gs
                              WHERE gs.org_id=le.org_id
                                AND gs.id=le.source_gateway_settlement_id
                                AND gs.request_id=#{requestId}))
             OR (le.source_reconciliation_adjustment_id IS NOT NULL
                  AND EXISTS (SELECT 1 FROM reconciliation_adjustment ra
                              WHERE ra.org_id=le.org_id
                                AND ra.id=le.source_reconciliation_adjustment_id
                                AND ra.gateway_request_id=#{requestId})))
            """)
    BigDecimal selectRequestPostedInternalAmount(
            @Param("organizationId") long organizationId,
            @Param("requestId") long requestId);

    /**
     * Direct provider-lineage sources of the Ledger entries a correction group
     * produced. Linking a correction to a reconciliation case is only legal
     * when every entry resolves to the case's provider account and currency.
     */
    @Select("""
            SELECT le.id AS entry_id,
                   le.currency AS currency,
                   le.source_charge_fact_id AS source_charge_fact_id,
                   le.source_gateway_settlement_id AS source_gateway_settlement_id,
                   le.source_reconciliation_adjustment_id AS source_reconciliation_adjustment_id
            FROM ledger_entry le
            WHERE le.org_id=#{organizationId} AND le.correction_group_id=#{correctionGroupId}
            ORDER BY le.entry_index ASC
            """)
    List<CorrectionEntrySource> selectCorrectionEntrySources(
            @Param("organizationId") long organizationId,
            @Param("correctionGroupId") long correctionGroupId);

    record CorrectionEntrySource(
            long entryId,
            String currency,
            Long sourceChargeFactId,
            Long sourceGatewaySettlementId,
            Long sourceReconciliationAdjustmentId) {
    }

    @Select("""
            SELECT ib.provider_account_id AS provider_account_id, cf.currency AS currency
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{sourceId}
            """)
    SourceScope selectChargeProviderScope(
            @Param("organizationId") long organizationId,
            @Param("sourceId") long sourceId);

    @Select("""
            SELECT gs.provider_account_id AS provider_account_id, gs.currency AS currency
            FROM gateway_settlement gs
            WHERE gs.org_id=#{organizationId} AND gs.id=#{sourceId}
            """)
    SourceScope selectGatewaySettlementScope(
            @Param("organizationId") long organizationId,
            @Param("sourceId") long sourceId);

    record SourceScope(long providerAccountId, String currency) {
    }

    record ReconciliationEvidenceRow(
            long organizationId,
            long runId,
            Long caseId,
            String evidenceKey,
            long providerAccountId,
            String currency,
            String matchKind,
            String differenceKind,
            Long chargeFactId,
            Long gatewayRequestId,
            Long gatewayRouteAttemptId,
            Long gatewayUsageFactId,
            Long gatewaySettlementId,
            Long correctionGroupId,
            Long reconciliationAdjustmentId,
            Long gatewayFinancialResolutionId,
            Long ledgerPostingId,
            String providerRequestId,
            String evidenceReference,
            BigDecimal externalAmount,
            BigDecimal internalAmount,
            BigDecimal differenceAmount,
            Instant createdAt) {
    }
}
