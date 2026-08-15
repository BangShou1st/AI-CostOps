package com.aicostops.evidence.infrastructure;

import com.aicostops.evidence.domain.Evidence;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EvidenceMapper {

    String EVIDENCE_COLUMNS = """
            e.id,e.org_id,e.sha256,e.object_key,e.original_filename,e.media_type,
            e.size_bytes,e.uploaded_by_member_id,e.storage_status,e.storage_error_code,
            e.created_at,e.updated_at
            """;

    @Select("""
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM evidence e
            WHERE e.org_id=#{organizationId} AND e.sha256=#{sha256}
            """)
    Evidence findByOrganizationAndSha(
            @Param("organizationId") long organizationId,
            @Param("sha256") String sha256);

    /**
     * Current read (locking) used only to converge a concurrent duplicate-key race:
     * consistent reads in REPEATABLE READ keep the old snapshot, so the winner's
     * committed row would stay invisible inside the same transaction.
     */
    @Select("""
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM evidence e
            WHERE e.org_id=#{organizationId} AND e.sha256=#{sha256}
            FOR UPDATE
            """)
    Evidence findByOrganizationAndShaCurrent(
            @Param("organizationId") long organizationId,
            @Param("sha256") String sha256);

    @Select("""
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM evidence e
            WHERE e.id=#{evidenceId} AND e.org_id=#{organizationId}
            """)
    Evidence findByIdAndOrganization(
            @Param("evidenceId") long evidenceId,
            @Param("organizationId") long organizationId);

    @Select("""
            SELECT COUNT(*)
            FROM evidence e
            WHERE e.org_id=#{organizationId}
            """)
    long countByOrganization(@Param("organizationId") long organizationId);

    @Select("""
            SELECT
            """ + EVIDENCE_COLUMNS + """
            FROM evidence e
            WHERE e.org_id=#{organizationId}
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<Evidence> pageByOrganization(
            @Param("organizationId") long organizationId,
            @Param("offset") long offset,
            @Param("size") int size);

    @Insert("""
            INSERT INTO evidence(
                org_id,sha256,object_key,original_filename,media_type,size_bytes,
                uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
            VALUES (
                #{organizationId},#{sha256},#{objectKey},#{originalFilename},#{mediaType},#{sizeBytes},
                #{uploadedByMemberId},'STAGING',NULL,#{now},#{now})
            """)
    int insertStaging(
            @Param("organizationId") long organizationId,
            @Param("sha256") String sha256,
            @Param("objectKey") String objectKey,
            @Param("originalFilename") String originalFilename,
            @Param("mediaType") String mediaType,
            @Param("sizeBytes") long sizeBytes,
            @Param("uploadedByMemberId") long uploadedByMemberId,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE evidence e
            SET e.storage_status='AVAILABLE', e.storage_error_code=NULL, e.updated_at=#{now}
            WHERE e.id=#{evidenceId} AND e.org_id=#{organizationId}
              AND e.storage_status IN ('STAGING','FAILED')
            """)
    int markAvailable(
            @Param("evidenceId") long evidenceId,
            @Param("organizationId") long organizationId,
            @Param("now") Instant now);

    @Update("""
            UPDATE evidence e
            SET e.storage_status='FAILED', e.storage_error_code=#{errorCode}, e.updated_at=#{now}
            WHERE e.id=#{evidenceId} AND e.org_id=#{organizationId}
              AND e.storage_status <> 'AVAILABLE'
            """)
    int markFailedUnlessAvailable(
            @Param("evidenceId") long evidenceId,
            @Param("organizationId") long organizationId,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now);
}
