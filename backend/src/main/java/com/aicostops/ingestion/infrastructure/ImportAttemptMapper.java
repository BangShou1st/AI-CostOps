package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.domain.ImportAttempt;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImportAttemptMapper {

    String IMPORT_ATTEMPT_COLUMNS = """
            ia.id,ia.import_batch_id,ia.attempt_no,ia.status,ia.trigger_type,
            ia.predecessor_attempt_id,ia.available_at,ia.lease_owner,ia.lease_until,ia.lease_version,
            ia.parser_version,ia.detected_provider_code,ia.schema_fingerprint,
            ia.started_at,ia.finished_at,ia.error_code,ia.error_summary,
            ia.records_seen,ia.records_valid,ia.warning_count,ia.error_count,ia.created_at
            """;

    @Insert("""
            INSERT INTO import_attempt(
                import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                available_at,lease_owner,lease_until,lease_version,parser_version,
                detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                records_seen,records_valid,warning_count,error_count,created_at)
            VALUES (
                #{batchId},#{attemptNo},'QUEUED',#{triggerType},#{predecessorAttemptId},
                #{availableAt},NULL,NULL,0,#{parserVersion},
                NULL,NULL,NULL,NULL,NULL,NULL,
                0,0,0,0,#{now})
            """)
    int insertQueued(
            @Param("batchId") long batchId,
            @Param("attemptNo") int attemptNo,
            @Param("triggerType") String triggerType,
            @Param("predecessorAttemptId") Long predecessorAttemptId,
            @Param("parserVersion") String parserVersion,
            @Param("availableAt") Instant availableAt,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT
            """ + IMPORT_ATTEMPT_COLUMNS + """
            FROM import_attempt ia
            WHERE ia.import_batch_id=#{batchId}
            ORDER BY ia.attempt_no DESC, ia.id DESC
            LIMIT 1
            """)
    ImportAttempt findLatestByBatch(@Param("batchId") long batchId);

    @Select("""
            SELECT
            """ + IMPORT_ATTEMPT_COLUMNS + """
            FROM import_attempt ia
            WHERE ia.id=#{attemptId}
            """)
    ImportAttempt findById(@Param("attemptId") long attemptId);

    @Select("""
            SELECT COUNT(*)
            FROM import_attempt ia
            WHERE ia.import_batch_id=#{batchId}
              AND ia.status IN ('QUEUED','RUNNING')
            """)
    int countActiveByBatch(@Param("batchId") long batchId);

    @Select("""
            SELECT COUNT(*)
            FROM import_attempt ia
            WHERE ia.import_batch_id=#{batchId}
              AND ia.trigger_type='LEASE_RECOVERY'
            """)
    int countLeaseRecoveriesByBatch(@Param("batchId") long batchId);
}
