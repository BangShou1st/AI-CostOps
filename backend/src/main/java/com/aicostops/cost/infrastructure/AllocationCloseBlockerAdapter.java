package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.AllocationCloseBlockerPort;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AllocationCloseBlockerAdapter extends AllocationCloseBlockerPort {

    String RELEVANT = """
            cf.org_id=#{organizationId}
            AND ib.status='CONFIRMED'
            AND ib.confirmed_attempt_id=ia.id
            AND cf.review_status='CLEAN'
            AND cf.period_start >= #{periodStart}
            AND cf.period_start < #{periodEnd}
            AND (cf.current_allocation_decision_id IS NULL OR ad.status <> 'CONFIRMED')
            """;

    @Override
    @Select("""
            SELECT COUNT(*)
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            LEFT JOIN allocation_decision ad
              ON ad.id=cf.current_allocation_decision_id AND ad.org_id=cf.org_id
            WHERE
            """ + RELEVANT)
    long countUnallocatedCleanCharges(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Override
    @Select("""
            SELECT cf.id
            FROM charge_fact cf
            JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
            JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
            JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=cf.org_id
            LEFT JOIN allocation_decision ad
              ON ad.id=cf.current_allocation_decision_id AND ad.org_id=cf.org_id
            WHERE
            """ + RELEVANT + """
            ORDER BY cf.period_start,cf.id
            LIMIT #{limit}
            """)
    List<Long> sampleUnallocatedChargeIds(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("limit") int limit);
}
