package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.application.LedgerIntegrityPort;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LedgerIntegrityAdapter extends LedgerIntegrityPort {

    /**
     * A period-scoped CORRECTION posting is the anchor. The canonical group is
     * recovered from posting_key=CORRECTION:{groupId}; source_id is then checked
     * against the group's target_entry_id. Anchoring from the posting means a
     * corrupted source_id cannot make the problem disappear from the scan.
     */
    String CORRECTION_PROBLEM_FROM = """
            FROM ledger_posting lp
            LEFT JOIN correction_group cg
              ON cg.org_id=lp.org_id
             AND lp.posting_key=CONCAT('CORRECTION:',cg.id)
            LEFT JOIN ledger_entry target
              ON target.id=cg.target_entry_id AND target.org_id=cg.org_id
            LEFT JOIN ledger_entry le
              ON le.posting_id=lp.id AND le.org_id=lp.org_id
            WHERE lp.org_id=#{organizationId}
              AND lp.billing_period_id=#{billingPeriodId}
              AND lp.source_type='CORRECTION'
            GROUP BY lp.id,lp.posting_key,lp.source_id,cg.id,cg.target_entry_id,cg.target_posting_id,
                     target.id,target.posting_id,target.amount,target.currency,
                     target.project_id,target.cost_center_id,target.team_id,
                     target.source_charge_fact_id,target.source_expense_claim_id,
                     target.allocation_line_id
            HAVING cg.id IS NULL
                OR target.id IS NULL
                OR lp.source_id <> cg.target_entry_id
                OR cg.target_posting_id <> target.posting_id
                OR COUNT(le.id) NOT BETWEEN 1 AND 2
                OR SUM(CASE WHEN le.entry_type='REVERSAL' THEN 1 ELSE 0 END) <> 1
                OR SUM(CASE WHEN le.entry_type='ADJUSTMENT' THEN 1 ELSE 0 END) <> COUNT(le.id)-1
                OR SUM(CASE WHEN le.entry_type NOT IN ('REVERSAL','ADJUSTMENT') THEN 1 ELSE 0 END) > 0
                OR SUM(CASE WHEN le.entry_type='REVERSAL' AND (
                     le.entry_index <> 0
                     OR le.reverses_entry_id IS NULL
                     OR le.reverses_entry_id <> target.id
                     OR le.amount <> -target.amount
                     OR le.currency <> target.currency
                     OR NOT (le.project_id <=> target.project_id)
                     OR NOT (le.cost_center_id <=> target.cost_center_id)
                     OR NOT (le.team_id <=> target.team_id)
                     OR NOT (le.source_charge_fact_id <=> target.source_charge_fact_id)
                     OR NOT (le.source_expense_claim_id <=> target.source_expense_claim_id)
                     OR NOT (le.allocation_line_id <=> target.allocation_line_id)
                     OR le.correction_group_id IS NULL
                     OR le.correction_group_id <> cg.id
                   ) THEN 1 ELSE 0 END) > 0
                OR SUM(CASE WHEN le.entry_type='ADJUSTMENT' AND (
                     le.entry_index <> 1
                     OR le.reverses_entry_id IS NOT NULL
                     OR le.currency <> target.currency
                     OR NOT (le.source_charge_fact_id <=> target.source_charge_fact_id)
                     OR NOT (le.source_expense_claim_id <=> target.source_expense_claim_id)
                     OR le.allocation_line_id IS NOT NULL
                     OR le.correction_group_id IS NULL
                     OR le.correction_group_id <> cg.id
                   ) THEN 1 ELSE 0 END) > 0
            """;

    @Override
    @Select("""
            SELECT
              (
                SELECT COUNT(*)
                FROM ledger_posting lp
                LEFT JOIN ledger_entry le
                  ON le.posting_id=lp.id AND le.org_id=lp.org_id
                WHERE lp.org_id=#{organizationId}
                  AND lp.billing_period_id=#{billingPeriodId}
                  AND le.id IS NULL
              ) AS postings_without_entries,
              (
                SELECT COUNT(*)
                FROM ledger_entry le
                JOIN ledger_posting lp
                  ON lp.id=le.posting_id AND lp.org_id=le.org_id
                LEFT JOIN allocation_line al
                  ON al.id=le.allocation_line_id AND al.org_id=le.org_id
                WHERE le.org_id=#{organizationId}
                  AND lp.billing_period_id=#{billingPeriodId}
                  AND lp.source_type IN ('PROVIDER_CHARGE','EXPENSE_CLAIM')
                  AND (
                    al.id IS NULL
                    OR lp.allocation_decision_id IS NULL
                    OR al.decision_id <> lp.allocation_decision_id
                    OR le.entry_index <> al.line_index
                    OR le.amount <> al.allocated_amount
                    OR le.currency <> al.currency
                    OR NOT (le.project_id <=> al.project_id)
                    OR NOT (le.cost_center_id <=> al.cost_center_id)
                    OR NOT (le.team_id <=> al.team_id)
                    OR (lp.source_type='PROVIDER_CHARGE' AND (
                        le.source_charge_fact_id IS NULL
                        OR le.source_charge_fact_id <> lp.source_id
                        OR le.source_expense_claim_id IS NOT NULL))
                    OR (lp.source_type='EXPENSE_CLAIM' AND (
                        le.source_expense_claim_id IS NULL
                        OR le.source_expense_claim_id <> lp.source_id
                        OR le.source_charge_fact_id IS NOT NULL))
                  )
              ) AS normal_entry_mismatches,
              (
                SELECT COUNT(*) FROM (
                  SELECT lp.id
                  FROM ledger_posting lp
                  LEFT JOIN ledger_entry le
                    ON le.posting_id=lp.id AND le.org_id=lp.org_id
                  LEFT JOIN allocation_line al
                    ON al.decision_id=lp.allocation_decision_id AND al.org_id=lp.org_id
                  WHERE lp.org_id=#{organizationId}
                    AND lp.billing_period_id=#{billingPeriodId}
                    AND lp.source_type IN ('PROVIDER_CHARGE','EXPENSE_CLAIM')
                  GROUP BY lp.id
                  HAVING COUNT(DISTINCT le.id) <> COUNT(DISTINCT al.id)
                ) x
              ) AS normal_posting_cardinality_mismatches,
              (
                SELECT COUNT(*) FROM (
                  SELECT lp.id
                  """ + CORRECTION_PROBLEM_FROM + """
                ) x
              ) AS correction_mismatches,
              (
                SELECT COUNT(*) FROM (
                  SELECT cg.target_entry_id
                  FROM correction_group cg
                  JOIN ledger_posting lp
                    ON lp.org_id=cg.org_id
                   AND lp.source_type='CORRECTION'
                   AND lp.posting_key=CONCAT('CORRECTION:',cg.id)
                  WHERE cg.org_id=#{organizationId}
                    AND lp.billing_period_id=#{billingPeriodId}
                    AND (
                      SELECT COUNT(*) FROM correction_group all_cg
                      WHERE all_cg.org_id=cg.org_id
                        AND all_cg.target_entry_id=cg.target_entry_id
                    ) > 1
                  GROUP BY cg.target_entry_id
                ) x
              ) AS double_reversal_targets
            """)
    LedgerIntegritySnapshot inspect(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);

    @Override
    @Select("""
            SELECT problem_id FROM (
              SELECT lp.id AS problem_id
              FROM ledger_posting lp
              LEFT JOIN ledger_entry le
                ON le.posting_id=lp.id AND le.org_id=lp.org_id
              WHERE lp.org_id=#{organizationId}
                AND lp.billing_period_id=#{billingPeriodId}
              GROUP BY lp.id
              HAVING COUNT(le.id)=0
              UNION
              SELECT DISTINCT lp.id AS problem_id
              FROM ledger_entry le
              JOIN ledger_posting lp
                ON lp.id=le.posting_id AND lp.org_id=le.org_id
              LEFT JOIN allocation_line al
                ON al.id=le.allocation_line_id AND al.org_id=le.org_id
              WHERE le.org_id=#{organizationId}
                AND lp.billing_period_id=#{billingPeriodId}
                AND lp.source_type IN ('PROVIDER_CHARGE','EXPENSE_CLAIM')
                AND (
                  al.id IS NULL
                  OR lp.allocation_decision_id IS NULL
                  OR al.decision_id <> lp.allocation_decision_id
                  OR le.entry_index <> al.line_index
                  OR le.amount <> al.allocated_amount
                  OR le.currency <> al.currency
                  OR NOT (le.project_id <=> al.project_id)
                  OR NOT (le.cost_center_id <=> al.cost_center_id)
                  OR NOT (le.team_id <=> al.team_id)
                  OR (lp.source_type='PROVIDER_CHARGE' AND (
                      le.source_charge_fact_id IS NULL
                      OR le.source_charge_fact_id <> lp.source_id
                      OR le.source_expense_claim_id IS NOT NULL))
                  OR (lp.source_type='EXPENSE_CLAIM' AND (
                      le.source_expense_claim_id IS NULL
                      OR le.source_expense_claim_id <> lp.source_id
                      OR le.source_charge_fact_id IS NOT NULL))
                )
              UNION
              SELECT lp.id AS problem_id
              """ + CORRECTION_PROBLEM_FROM + """
            ) problems
            ORDER BY problem_id
            LIMIT #{limit}
            """)
    List<Long> sampleProblemPostingIds(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId,
            @Param("limit") int limit);
}
