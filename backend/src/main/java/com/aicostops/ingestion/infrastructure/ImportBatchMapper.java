package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.domain.ImportBatch;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImportBatchMapper {

    String IMPORT_BATCH_COLUMNS = """
            ib.id,ib.org_id,ib.evidence_id,ib.provider_account_id,ib.expected_provider_code,
            ib.source_type,ib.parser_version,ib.status,ib.period_start,ib.period_end,
            ib.created_by_member_id,ib.created_at,ib.updated_at
            """;

    @Select("""
            SELECT
            """ + IMPORT_BATCH_COLUMNS + """
            FROM import_batch ib
            WHERE ib.evidence_id=#{evidenceId}
              AND ib.provider_account_id=#{providerAccountId}
              AND ib.source_type=#{sourceType}
              AND ib.parser_version=#{parserVersion}
            """)
    ImportBatch findByIdentity(
            @Param("evidenceId") long evidenceId,
            @Param("providerAccountId") long providerAccountId,
            @Param("sourceType") String sourceType,
            @Param("parserVersion") String parserVersion);

    /**
     * Current read (locking) used only to converge a concurrent duplicate-key race;
     * consistent reads in REPEATABLE READ keep the old snapshot.
     */
    @Select("""
            SELECT
            """ + IMPORT_BATCH_COLUMNS + """
            FROM import_batch ib
            WHERE ib.evidence_id=#{evidenceId}
              AND ib.provider_account_id=#{providerAccountId}
              AND ib.source_type=#{sourceType}
              AND ib.parser_version=#{parserVersion}
            FOR UPDATE
            """)
    ImportBatch findByIdentityForUpdate(
            @Param("evidenceId") long evidenceId,
            @Param("providerAccountId") long providerAccountId,
            @Param("sourceType") String sourceType,
            @Param("parserVersion") String parserVersion);

    @Select("""
            SELECT
            """ + IMPORT_BATCH_COLUMNS + """
            FROM import_batch ib
            WHERE ib.id=#{batchId} AND ib.org_id=#{organizationId}
            """)
    ImportBatch findByIdAndOrganization(
            @Param("batchId") long batchId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT
            """ + IMPORT_BATCH_COLUMNS + """
            FROM import_batch ib
            WHERE ib.id=#{batchId}
            """)
    ImportBatch findById(@Param("batchId") long batchId);

    @Select("""
            SELECT
            """ + IMPORT_BATCH_COLUMNS + """
            FROM import_batch ib
            WHERE ib.id=#{batchId}
            FOR UPDATE
            """)
    ImportBatch findByIdForUpdate(@Param("batchId") long batchId);

    @Insert("""
            INSERT INTO import_batch(
                org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
            VALUES (
                #{organizationId},#{evidenceId},#{providerAccountId},#{expectedProviderCode},#{sourceType},
                #{parserVersion},'PENDING',NULL,NULL,#{createdByMemberId},#{now},#{now})
            """)
    int insert(
            @Param("organizationId") long organizationId,
            @Param("evidenceId") long evidenceId,
            @Param("providerAccountId") long providerAccountId,
            @Param("expectedProviderCode") String expectedProviderCode,
            @Param("sourceType") String sourceType,
            @Param("parserVersion") String parserVersion,
            @Param("createdByMemberId") long createdByMemberId,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE import_batch ib
            SET ib.status=#{status}, ib.updated_at=#{now}
            WHERE ib.id=#{batchId}
            """)
    int updateStatus(
            @Param("batchId") long batchId,
            @Param("status") String status,
            @Param("now") Instant now);
}
