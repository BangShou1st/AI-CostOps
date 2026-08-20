package com.aicostops.cost.review.infrastructure;

import com.aicostops.cost.review.application.DuplicateCloseBlockerPort;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DuplicateCloseBlockerAdapter extends DuplicateCloseBlockerPort {

    String RELEVANT = """
            dc.org_id=#{organizationId}
            AND dc.status='OPEN'
            AND (
                (cf.period_start >= #{periodStart} AND cf.period_start < #{periodEnd})
                OR
                (mf.period_start >= #{periodStart} AND mf.period_start < #{periodEnd})
            )
            """;

    @Override
    @Select("""
            SELECT COUNT(*)
            FROM duplicate_candidate dc
            JOIN charge_fact cf ON cf.id=dc.charge_fact_id AND cf.org_id=dc.org_id
            JOIN charge_fact mf ON mf.id=dc.matched_charge_id AND mf.org_id=dc.org_id
            WHERE
            """ + RELEVANT)
    long countUnresolvedDuplicates(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Override
    @Select("""
            SELECT dc.id
            FROM duplicate_candidate dc
            JOIN charge_fact cf ON cf.id=dc.charge_fact_id AND cf.org_id=dc.org_id
            JOIN charge_fact mf ON mf.id=dc.matched_charge_id AND mf.org_id=dc.org_id
            WHERE
            """ + RELEVANT + """
            ORDER BY dc.created_at,dc.id
            LIMIT #{limit}
            """)
    List<Long> sampleUnresolvedDuplicateIds(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("limit") int limit);
}
