package com.aicostops.budget.infrastructure;

import com.aicostops.budget.application.BudgetIntegrityPort;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BudgetIntegrityAdapter extends BudgetIntegrityPort {

    @Override
    @Select("""
            SELECT
              (
                SELECT COUNT(*)
                FROM budget b
                LEFT JOIN (
                  SELECT le.budget_id,SUM(le.amount) AS expected_actual
                  FROM ledger_entry le
                  WHERE le.org_id=#{organizationId} AND le.budget_id IS NOT NULL
                  GROUP BY le.budget_id
                ) actuals ON actuals.budget_id=b.id
                WHERE b.org_id=#{organizationId}
                  AND b.billing_period_id=#{billingPeriodId}
                  AND b.actual_amount <> COALESCE(actuals.expected_actual,0)
              ) AS actual_amount_drift,
              (
                SELECT COUNT(*)
                FROM budget b
                LEFT JOIN (
                  SELECT bc.budget_id,SUM(bc.remaining_amount) AS expected_committed
                  FROM budget_commitment bc
                  WHERE bc.org_id=#{organizationId}
                    AND bc.status IN ('ACTIVE','PARTIALLY_CONSUMED')
                  GROUP BY bc.budget_id
                ) committed ON committed.budget_id=b.id
                WHERE b.org_id=#{organizationId}
                  AND b.billing_period_id=#{billingPeriodId}
                  AND b.committed_amount <> COALESCE(committed.expected_committed,0)
              ) AS committed_amount_drift,
              (
                SELECT COUNT(*)
                FROM budget_commitment bc
                JOIN budget b ON b.id=bc.budget_id AND b.org_id=bc.org_id
                WHERE bc.org_id=#{organizationId}
                  AND b.billing_period_id=#{billingPeriodId}
                  AND (
                    (bc.status IN ('ACTIVE','PARTIALLY_CONSUMED')
                        AND (bc.approved_amount IS NULL OR bc.remaining_amount IS NULL
                             OR bc.remaining_amount <= 0 OR bc.remaining_amount > bc.approved_amount))
                    OR (bc.status='CONSUMED' AND (bc.remaining_amount IS NULL OR bc.remaining_amount <> 0))
                    OR (bc.status IN ('RELEASED','REJECTED','CANCELED')
                        AND bc.remaining_amount IS NOT NULL AND bc.remaining_amount <> 0)
                  )
              ) AS invalid_commitment_state
            """)
    BudgetIntegritySnapshot inspect(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Override
    @Select("""
            SELECT budget_id FROM (
              SELECT b.id AS budget_id
              FROM budget b
              LEFT JOIN (
                SELECT le.budget_id,SUM(le.amount) AS expected_actual
                FROM ledger_entry le
                WHERE le.org_id=#{organizationId} AND le.budget_id IS NOT NULL
                GROUP BY le.budget_id
              ) a ON a.budget_id=b.id
              LEFT JOIN (
                SELECT bc.budget_id,SUM(bc.remaining_amount) AS expected_committed
                FROM budget_commitment bc
                WHERE bc.org_id=#{organizationId}
                  AND bc.status IN ('ACTIVE','PARTIALLY_CONSUMED')
                GROUP BY bc.budget_id
              ) c ON c.budget_id=b.id
              WHERE b.org_id=#{organizationId}
                AND b.billing_period_id=#{billingPeriodId}
                AND (
                  b.actual_amount <> COALESCE(a.expected_actual,0)
                  OR b.committed_amount <> COALESCE(c.expected_committed,0)
                )
              UNION
              SELECT DISTINCT b.id AS budget_id
              FROM budget_commitment bc
              JOIN budget b ON b.id=bc.budget_id AND b.org_id=bc.org_id
              WHERE bc.org_id=#{organizationId}
                AND b.billing_period_id=#{billingPeriodId}
                AND (
                  (bc.status IN ('ACTIVE','PARTIALLY_CONSUMED')
                    AND (bc.approved_amount IS NULL OR bc.remaining_amount IS NULL
                         OR bc.remaining_amount <= 0 OR bc.remaining_amount > bc.approved_amount))
                  OR (bc.status='CONSUMED' AND (bc.remaining_amount IS NULL OR bc.remaining_amount <> 0))
                )
            ) problems
            ORDER BY budget_id
            LIMIT #{limit}
            """)
    List<Long> sampleProblemBudgetIds(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("limit") int limit);
}
