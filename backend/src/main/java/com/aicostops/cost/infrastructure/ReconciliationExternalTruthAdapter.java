package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReconciliationExternalTruthAdapter extends ReconciliationExternalTruthPort {

    @Override
    @Select("""
            SELECT ib.provider_account_id AS provider_account_id,
                   cf.currency AS currency,
                   COUNT(*) AS row_count,
                   SUM(cf.amount) AS amount
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId}
              AND ib.status='CONFIRMED'
              AND ib.confirmed_attempt_id=ia.id
              AND ib.provider_account_id IS NOT NULL
              AND cf.review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
              AND cf.period_start >= #{periodStart}
              AND cf.period_start < #{periodEnd}
            GROUP BY ib.provider_account_id,cf.currency
            ORDER BY ib.provider_account_id,cf.currency
            """)
    List<ExternalAggregate> aggregateConfirmedCharges(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);
}
