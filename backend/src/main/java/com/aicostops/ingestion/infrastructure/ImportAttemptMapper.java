package com.aicostops.ingestion.infrastructure;

import com.aicostops.ingestion.domain.ImportAttempt;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("""
            SELECT
            """ + IMPORT_ATTEMPT_COLUMNS + """
            FROM import_attempt ia
            WHERE ia.status='QUEUED'
              AND ia.available_at <= UTC_TIMESTAMP(6)
            ORDER BY ia.available_at, ia.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    ImportAttempt claimNextQueued();

    @Update("""
            UPDATE import_attempt ia
            SET ia.status='RUNNING',
                ia.lease_owner=#{workerId},
                ia.lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMicros},UTC_TIMESTAMP(6)),
                ia.lease_version=ia.lease_version+1,
                ia.started_at=COALESCE(ia.started_at,UTC_TIMESTAMP(6))
            WHERE ia.id=#{attemptId}
            """)
    int markRunning(
            @Param("attemptId") long attemptId,
            @Param("workerId") String workerId,
            @Param("leaseMicros") long leaseMicros);

    @Update("""
            UPDATE import_attempt ia
            SET ia.lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMicros},UTC_TIMESTAMP(6))
            WHERE ia.id=#{attemptId}
              AND ia.status='RUNNING'
              AND ia.lease_owner=#{workerId}
              AND ia.lease_version=#{leaseVersion}
              AND ia.lease_until > UTC_TIMESTAMP(6)
            """)
    int renewLease(
            @Param("attemptId") long attemptId,
            @Param("workerId") String workerId,
            @Param("leaseVersion") long leaseVersion,
            @Param("leaseMicros") long leaseMicros);

    @Select("""
            SELECT COUNT(*)
            FROM import_attempt ia
            WHERE ia.id=#{attemptId}
              AND ia.status='RUNNING'
              AND ia.lease_owner=#{workerId}
              AND ia.lease_version=#{leaseVersion}
              AND ia.lease_until > UTC_TIMESTAMP(6)
            """)
    int countOwnedRunning(
            @Param("attemptId") long attemptId,
            @Param("workerId") String workerId,
            @Param("leaseVersion") long leaseVersion);

    @Select("""
            SELECT
            """ + IMPORT_ATTEMPT_COLUMNS + """
            FROM import_attempt ia
            WHERE ia.status='RUNNING'
              AND ia.lease_until < UTC_TIMESTAMP(6)
            ORDER BY ia.lease_until, ia.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    ImportAttempt findExpiredRunning();

    @Update("""
            UPDATE import_attempt ia
            SET ia.status='FAILED',
                ia.finished_at=UTC_TIMESTAMP(6),
                ia.error_code=#{errorCode},
                ia.error_summary=#{errorSummary}
            WHERE ia.id=#{attemptId}
              AND ia.status='RUNNING'
            """)
    int failRunningAttempt(
            @Param("attemptId") long attemptId,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary);
}
