package com.aicostops.advisor.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.aicostops.advisor.infrastructure.AdvisorRows.JobRow;
import com.aicostops.advisor.infrastructure.AdvisorRows.AttemptRow;
import com.aicostops.advisor.infrastructure.AdvisorRows.ExplanationRow;

@Mapper
public interface AdvisorJobMapper {
    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Insert("""
            INSERT INTO advisor_inference_job(org_id,requested_by,subject_type,subject_id,
              evidence_fingerprint,evidence_refs_json,advisor_profile_version,advisor_profile_id,
              status,claim_token,claim_expires_at,
              gateway_request_id,attempt_count,failure_code,created_at,started_at,completed_at)
            VALUES(#{organizationId},#{requestedByUserId},#{subjectType},#{subjectId},#{fingerprint},
              CAST(#{refsJson} AS JSON),#{profileVersion},#{profileId},'PENDING',NULL,NULL,NULL,1,NULL,#{now},NULL,NULL)
            """)
    // requested_by stores app_user.id (human requester), never organization_member.id:
    // completion audits it as audit_event.actor_user_id which FKs app_user(id).
    int insertJob(@Param("organizationId") long organizationId, @Param("requestedByUserId") long requestedByUserId,
            @Param("subjectType") String subjectType, @Param("subjectId") long subjectId,
            @Param("fingerprint") String fingerprint, @Param("refsJson") String refsJson,
            @Param("profileVersion") int profileVersion, @Param("profileId") long profileId,
            @Param("now") Instant now);

    /**
     * Deterministically retires undispatched work bound to a superseded profile revision (plus
     * legacy rows without a profile binding): they must never silently execute under the new
     * ACTIVE revision. Already-dispatched/linked jobs converge on their own execution instead.
     */
    @Update("""
            UPDATE advisor_inference_job
            SET status='FAILED',failure_code='PROFILE_SUPERSEDED',completed_at=#{now}
            WHERE org_id=#{organizationId} AND status='PENDING'
              AND (advisor_profile_id=#{prevProfileId} OR advisor_profile_id IS NULL)
            """)
    int supersedePendingJobs(@Param("organizationId") long organizationId,
            @Param("prevProfileId") long prevProfileId, @Param("now") Instant now);

    /**
     * P1-1 terminal convergence: every superseded PENDING job's current PENDING attempt must
     * fail in the same DB transaction. Matches jobs already marked FAILED/PROFILE_SUPERSEDED
     * for the same superseded revision (plus legacy unbound rows) so job+attempt never split
     * as FAILED/PENDING. Provider I/O never occurs in this transaction.
     */
    @Update("""
            UPDATE advisor_inference_attempt a
            INNER JOIN advisor_inference_job j ON j.id = a.job_id AND j.org_id = a.org_id
            SET a.status='FAILED', a.failure_code='PROFILE_SUPERSEDED'
            WHERE a.org_id=#{organizationId}
              AND a.status='PENDING'
              AND j.status='FAILED'
              AND j.failure_code='PROFILE_SUPERSEDED'
              AND (j.advisor_profile_id=#{prevProfileId} OR j.advisor_profile_id IS NULL)
            """)
    int supersedePendingAttempts(@Param("organizationId") long organizationId,
            @Param("prevProfileId") long prevProfileId);

    @Select("SELECT CAST(evidence_refs_json AS CHAR) FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}")
    String findEvidenceRefs(@Param("id") long id, @Param("organizationId") long organizationId);

    @Insert("INSERT INTO advisor_inference_attempt(org_id,job_id,attempt_no,gateway_request_id,status,failure_code,created_at) VALUES(#{organizationId},#{jobId},#{attemptNo},NULL,'PENDING',NULL,#{now})")
    int insertAttempt(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo, @Param("now") Instant now);

    @Select("SELECT id,org_id,requested_by AS requestedByUserId,subject_type,subject_id,evidence_fingerprint,advisor_profile_version,advisor_profile_id,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,failure_code,created_at,started_at,completed_at FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}")
    JobRow findJob(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id,requested_by AS requestedByUserId,subject_type,subject_id,evidence_fingerprint,advisor_profile_version,
              advisor_profile_id,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,
              failure_code,created_at,started_at,completed_at
            FROM advisor_inference_job
            WHERE (status='PENDING' AND gateway_request_id IS NULL)
              OR (status='CLAIMED' AND claim_expires_at < #{now} AND gateway_request_id IS NULL)
            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    JobRow claimEligibleAny(@Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='CLAIMED',claim_token=#{token},claim_expires_at=#{lease},started_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND ((status='PENDING') OR (status='CLAIMED' AND claim_expires_at < #{now} AND gateway_request_id IS NULL))")
    int markClaimed(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("token") String token, @Param("lease") Instant lease, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status=#{status},gateway_request_id=#{gatewayRequestId},claim_expires_at=#{lease} WHERE id=#{id} AND org_id=#{organizationId} AND claim_token=#{token} AND status IN ('CLAIMED','DISPATCHING','RUNNING')")
    int linkGateway(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("token") String token, @Param("status") String status,
            @Param("gatewayRequestId") long gatewayRequestId, @Param("lease") Instant lease);

    @Update("UPDATE advisor_inference_attempt SET gateway_request_id=#{gatewayRequestId},status='RUNNING' WHERE org_id=#{organizationId} AND job_id=#{jobId} AND attempt_no=#{attemptNo}")
    int linkAttempt(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo, @Param("gatewayRequestId") long gatewayRequestId);

    @Update("UPDATE advisor_inference_attempt SET status='COMPLETED' WHERE org_id=#{organizationId} AND job_id=#{jobId} AND attempt_no=#{attemptNo} AND status IN ('PENDING','RUNNING')")
    int completeAttempt(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo);

    @Update("UPDATE advisor_inference_attempt SET status='FAILED',failure_code=#{failureCode} WHERE org_id=#{organizationId} AND job_id=#{jobId} AND attempt_no=#{attemptNo} AND status IN ('PENDING','RUNNING')")
    int failAttempt(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo, @Param("failureCode") String failureCode);

    @Update("UPDATE advisor_inference_job SET status='COMPLETED',completed_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND claim_token=#{token} AND status IN ('CLAIMED','DISPATCHING','RUNNING')")
    int markCompleted(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("token") String token, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='FAILED',failure_code=#{failureCode},completed_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND claim_token=#{token} AND status IN ('PENDING','CLAIMED','DISPATCHING','RUNNING')")
    int markFailed(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("token") String token, @Param("failureCode") String failureCode, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='PENDING',gateway_request_id=NULL,claim_token=NULL,claim_expires_at=NULL,failure_code=NULL,started_at=NULL,completed_at=NULL,attempt_count=attempt_count+1 WHERE id=#{id} AND org_id=#{organizationId} AND status IN ('COMPLETED','FAILED')")
    int reopenForRetry(@Param("id") long id, @Param("organizationId") long organizationId);

    @Update("UPDATE advisor_inference_job SET status='PENDING',claim_token=NULL,claim_expires_at=NULL WHERE org_id=#{organizationId} AND status='CLAIMED' AND claim_expires_at < #{now} AND gateway_request_id IS NULL")
    int reclaimOrphans(@Param("organizationId") long organizationId, @Param("now") Instant now);

    @Select("SELECT id,org_id,job_id,attempt_no,gateway_request_id,status,failure_code,created_at FROM advisor_inference_attempt WHERE org_id=#{organizationId} AND job_id=#{jobId} ORDER BY attempt_no")
    List<AttemptRow> listAttempts(@Param("organizationId") long organizationId, @Param("jobId") long jobId);

    @Insert("""
            INSERT INTO advisor_explanation(org_id,job_id,attempt_no,schema_version,summary,
              drivers_explanation,recommended_actions_json,warnings_json,fact_reference_ids_json,created_at)
            VALUES(#{organizationId},#{jobId},#{attemptNo},1,#{summary},#{drivers},
              CAST(#{actionsJson} AS JSON),CAST(#{warningsJson} AS JSON),CAST(#{refsJson} AS JSON),#{now})
            """)
    int insertExplanation(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo, @Param("summary") String summary,
            @Param("drivers") String drivers, @Param("actionsJson") String actionsJson,
            @Param("warningsJson") String warningsJson, @Param("refsJson") String refsJson,
            @Param("now") Instant now);

    @Select("SELECT id,org_id,job_id,attempt_no,schema_version,summary,drivers_explanation,CAST(recommended_actions_json AS CHAR) AS recommended_actions_json,CAST(warnings_json AS CHAR) AS warnings_json,CAST(fact_reference_ids_json AS CHAR) AS fact_reference_ids_json,created_at FROM advisor_explanation WHERE org_id=#{organizationId} AND job_id=#{jobId} ORDER BY id DESC LIMIT 1")
    ExplanationRow findLatestExplanation(@Param("organizationId") long organizationId, @Param("jobId") long jobId);
}
