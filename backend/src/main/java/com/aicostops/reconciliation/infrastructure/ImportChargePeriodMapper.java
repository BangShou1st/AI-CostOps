package com.aicostops.reconciliation.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Reads the effective periods of canonical charges produced by one import attempt. */
@Mapper
public interface ImportChargePeriodMapper {

    @Select("""
            SELECT DISTINCT cf.period_start
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id
            WHERE cf.org_id=#{organizationId}
              AND ia.id=#{attemptId}
              AND ib.org_id=cf.org_id
              AND ib.id=ia.import_batch_id
              AND cf.review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
              AND cf.period_start IS NOT NULL
            ORDER BY cf.period_start
            """)
    List<Instant> findContributingPeriodStarts(
            @Param("organizationId") long organizationId,
            @Param("attemptId") long attemptId);
}
