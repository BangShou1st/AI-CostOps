package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.ChargePostingPort.ChargePostingSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Posting-only charge reads, kept behind the cost application seam. */
@Mapper
public interface ChargePostingMapper {

    String POSTING_COLUMNS = """
            cf.id,cf.amount,cf.currency,cf.period_start,cf.current_allocation_decision_id,
            cf.review_status,
            CASE WHEN ib.status='CONFIRMED' AND ib.confirmed_attempt_id=ia.id
                 THEN TRUE ELSE FALSE END AS confirmed_import
            """;

    @Select("""
            SELECT
            """ + POSTING_COLUMNS + """
            FROM charge_fact cf
            LEFT JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            LEFT JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            LEFT JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            """)
    ChargePostingSource selectForPosting(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);

    @Select("""
            SELECT
            """ + POSTING_COLUMNS + """
            FROM charge_fact cf
            LEFT JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            LEFT JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            LEFT JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            WHERE cf.org_id=#{organizationId} AND cf.id=#{chargeFactId}
            FOR UPDATE
            """)
    ChargePostingSource selectForPostingForUpdate(
            @Param("organizationId") long organizationId,
            @Param("chargeFactId") long chargeFactId);
}
