package com.aicostops.reporting.infrastructure;

import com.aicostops.reporting.application.WorkbenchReadModels.CurrencyAmount;
import com.aicostops.reporting.application.WorkbenchReadModels.PeriodSummary;
import com.aicostops.reporting.application.WorkbenchReadModels.ProjectCostLine;
import com.aicostops.reporting.application.WorkbenchReadModels.ProviderCostLine;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read-only workbench aggregations over existing financial truth. The
 * charge-side queries deliberately mirror the M6 close-blocker basis
 * (confirmed import, CLEAN review, period-bounded) so the workbench can never
 * disagree with Close about what the period contains. No statement here may
 * mutate any row.
 */
@Mapper
public interface WorkbenchQueryMapper {

    String CHARGE_BASIS = """
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId}
              AND ib.status='CONFIRMED'
              AND ib.confirmed_attempt_id=ia.id
              AND cf.review_status='CLEAN'
              AND cf.period_start >= #{periodStart}
              AND cf.period_start < #{periodEnd}
            """;

    @Select("""
            SELECT id AS billingPeriodId, period_start AS periodStart,
                   period_end AS periodEnd, status AS status
            FROM billing_period
            WHERE org_id=#{organizationId}
            ORDER BY period_start DESC, id DESC
            LIMIT 1
            """)
    PeriodSummary selectLatestPeriod(@Param("organizationId") long organizationId);

    @Select("""
            SELECT id AS billingPeriodId, period_start AS periodStart,
                   period_end AS periodEnd, status AS status
            FROM billing_period
            WHERE org_id=#{organizationId} AND id=#{billingPeriodId}
            LIMIT 1
            """)
    PeriodSummary selectPeriodById(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Select("""
            SELECT cf.provider_code AS providerCode, cf.currency AS currency,
                   SUM(cf.amount) AS totalAmount, COUNT(*) AS chargeCount
            FROM charge_fact cf
            """ + CHARGE_BASIS + """
            GROUP BY cf.provider_code, cf.currency
            ORDER BY SUM(cf.amount) DESC, cf.provider_code ASC
            LIMIT #{limit}
            """)
    List<ProviderCostLine> sumChargesByProvider(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("limit") int limit);

    @Select("""
            SELECT al.project_id AS projectId, p.name AS projectName,
                   al.currency AS currency, SUM(al.allocated_amount) AS totalAmount
            FROM allocation_line al
            JOIN allocation_decision ad
              ON ad.id=al.decision_id AND ad.org_id=al.org_id
            JOIN charge_fact cf
              ON cf.id=ad.charge_fact_id AND cf.org_id=ad.org_id
            JOIN project p
              ON p.id=al.project_id AND p.org_id=al.org_id
            WHERE al.org_id=#{organizationId}
              AND ad.status='CONFIRMED' AND ad.subject_type='CHARGE_FACT'
              AND al.project_id IS NOT NULL
              AND cf.period_start >= #{periodStart}
              AND cf.period_start < #{periodEnd}
            GROUP BY al.project_id, p.name, al.currency
            ORDER BY SUM(al.allocated_amount) DESC, al.project_id ASC
            LIMIT #{limit}
            """)
    List<ProjectCostLine> sumAllocationsByProject(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("limit") int limit);

    @Select("""
            SELECT cf.currency AS currency, SUM(cf.amount) AS amount,
                   COUNT(*) AS chargeCount
            FROM charge_fact cf
            LEFT JOIN allocation_decision ad
              ON ad.id=cf.current_allocation_decision_id AND ad.org_id=cf.org_id
            """ + CHARGE_BASIS + """
              AND (cf.current_allocation_decision_id IS NULL OR ad.status <> 'CONFIRMED')
            GROUP BY cf.currency
            ORDER BY SUM(cf.amount) DESC
            """)
    List<CurrencyAmount> sumUnallocatedByCurrency(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Select("""
            SELECT COUNT(*) FROM duplicate_candidate
            WHERE org_id=#{organizationId} AND status='OPEN'
            """)
    long countOpenDuplicateCandidates(@Param("organizationId") long organizationId);

    @Select("""
            SELECT status AS status, COUNT(*) AS itemCount
            FROM expense_claim
            WHERE org_id=#{organizationId} AND status IN ('SUBMITTED','NEEDS_INFO')
            GROUP BY status
            """)
    List<StatusCountRow> countExpenseClaimsByStatus(
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_run
            WHERE org_id=#{organizationId} AND status IN ('CREATED','RUNNING')
            """)
    long countActiveReconciliationRuns(@Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*) FROM reconciliation_case
            WHERE org_id=#{organizationId} AND status IN ('OPEN','INVESTIGATING')
            """)
    long countOpenReconciliationCases(@Param("organizationId") long organizationId);

    @Select("""
            SELECT id AS budgetId, scope_type AS scopeType, scope_id AS scopeId,
                   currency AS currency, total_amount AS totalAmount,
                   actual_amount AS actualAmount, committed_amount AS committedAmount
            FROM budget
            WHERE org_id=#{organizationId} AND billing_period_id=#{billingPeriodId}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<BudgetRow> selectBudgetsForPeriod(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("limit") int limit);

    record StatusCountRow(String status, long itemCount) {
    }

    record BudgetRow(long budgetId, String scopeType, long scopeId, String currency,
            String totalAmount, String actualAmount, String committedAmount) {
    }
}
