package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Hybrid provider-related Ledger truth for M15 reconciliation.
 *
 * <p>Internal truth is the immutable Ledger grouped by the entry's preserved
 * direct source lineage, never by the parent posting source_type: append-only
 * correction entries keep the direct source of the historical entry they
 * correct and therefore contribute through the same branches. Provider Charge
 * resolves its account through the confirmed import lineage; Gateway
 * Settlement and Reconciliation Adjustment carry their own provider account.
 * Entries without a recognized Provider-related direct source (for example
 * Expense claims) are never included.
 */
@Mapper
public interface ReconciliationInternalTruthAdapter extends ReconciliationInternalTruthPort {

    String HYBRID_PROVIDER_LEDGER_SQL = """
            SELECT source.provider_account_id AS provider_account_id,
                   source.currency AS currency,
                   COUNT(*) AS row_count,
                   SUM(source.amount) AS amount
            FROM (
                SELECT ib.provider_account_id AS provider_account_id,
                       le.currency AS currency,
                       le.amount AS amount
                FROM ledger_entry le
                JOIN ledger_posting lp
                  ON lp.id=le.posting_id AND lp.org_id=le.org_id
                JOIN charge_fact cf
                  ON cf.id=le.source_charge_fact_id AND cf.org_id=le.org_id
                JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
                JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
                JOIN import_batch ib
                  ON ib.id=ia.import_batch_id AND ib.org_id=le.org_id
                WHERE le.org_id=#{organizationId}
                  AND lp.billing_period_id=#{billingPeriodId}
                  AND le.source_charge_fact_id IS NOT NULL
                  AND ib.provider_account_id IS NOT NULL
                UNION ALL
                SELECT gs.provider_account_id AS provider_account_id,
                       le.currency AS currency,
                       le.amount AS amount
                FROM ledger_entry le
                JOIN ledger_posting lp
                  ON lp.id=le.posting_id AND lp.org_id=le.org_id
                JOIN gateway_settlement gs
                  ON gs.id=le.source_gateway_settlement_id AND gs.org_id=le.org_id
                WHERE le.org_id=#{organizationId}
                  AND lp.billing_period_id=#{billingPeriodId}
                  AND le.source_gateway_settlement_id IS NOT NULL
                UNION ALL
                SELECT ra.provider_account_id AS provider_account_id,
                       le.currency AS currency,
                       le.amount AS amount
                FROM ledger_entry le
                JOIN ledger_posting lp
                  ON lp.id=le.posting_id AND lp.org_id=le.org_id
                JOIN reconciliation_adjustment ra
                  ON ra.id=le.source_reconciliation_adjustment_id AND ra.org_id=le.org_id
                WHERE le.org_id=#{organizationId}
                  AND lp.billing_period_id=#{billingPeriodId}
                  AND le.source_reconciliation_adjustment_id IS NOT NULL
            ) source
            GROUP BY source.provider_account_id,source.currency
            ORDER BY source.provider_account_id,source.currency
            """;

    @Override
    @Select(HYBRID_PROVIDER_LEDGER_SQL)
    List<InternalAggregate> aggregateProviderLedger(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);
}
