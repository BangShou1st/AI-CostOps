package com.aicostops.ledger.infrastructure;

import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReconciliationInternalTruthAdapter extends ReconciliationInternalTruthPort {

    @Override
    @Select("""
            SELECT ib.provider_account_id AS provider_account_id,
                   le.currency AS currency,
                   COUNT(*) AS row_count,
                   SUM(le.amount) AS amount
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
            GROUP BY ib.provider_account_id,le.currency
            ORDER BY ib.provider_account_id,le.currency
            """)
    List<InternalAggregate> aggregateProviderLedger(
            @Param("organizationId") long organizationId,
            @Param("billingPeriodId") long billingPeriodId);
}
