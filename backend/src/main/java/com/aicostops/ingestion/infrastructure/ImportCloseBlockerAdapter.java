package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.application.ImportCloseBlockerPort;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImportCloseBlockerAdapter extends ImportCloseBlockerPort {

    String RELEVANT = """
            org_id=#{organizationId}
            AND status NOT IN ('CONFIRMED','CANCELED')
            AND (
                period_start IS NULL OR period_end IS NULL
                OR (period_start < #{periodEnd} AND period_end > #{periodStart})
            )
            """;

    @Override
    @Select("SELECT COUNT(*) FROM import_batch WHERE " + RELEVANT)
    long countOpenImports(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd);

    @Override
    @Select("""
            SELECT id FROM import_batch
            WHERE
            """ + RELEVANT + """
            ORDER BY created_at,id
            LIMIT #{limit}
            """)
    List<Long> sampleOpenImportIds(
            @Param("organizationId") long organizationId,
            @Param("periodStart") Instant periodStart,
            @Param("periodEnd") Instant periodEnd,
            @Param("limit") int limit);
}
