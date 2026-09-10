package com.aicostops.advisor.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AdvisorMapper {

    @Select("SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at FROM advisor_profile WHERE org_id=#{organizationId} AND status='ACTIVE' LIMIT 1")
    ProfileRow findActiveProfile(@Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at FROM advisor_profile WHERE id=#{id} AND org_id=#{organizationId}")
    ProfileRow findProfile(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT COALESCE(MAX(version),0)+1 FROM advisor_profile WHERE org_id=#{organizationId}")
    int nextProfileVersion(@Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO advisor_profile(org_id,version,provider_model_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at)
            VALUES(#{organizationId},#{version},#{providerModelId},#{projectId},#{scopeType},
              #{scopeId},#{budgetMode},'ACTIVE',#{createdBy},#{now},#{now})
            """)
    int insertProfile(@Param("organizationId") long organizationId, @Param("version") int version,
            @Param("providerModelId") long providerModelId, @Param("projectId") long projectId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId,
            @Param("budgetMode") String budgetMode, @Param("createdBy") long createdBy,
            @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("UPDATE advisor_profile SET status='RETIRED',updated_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    int retireProfile(@Param("id") long id, @Param("organizationId") long organizationId, @Param("now") Instant now);

    @Select("SELECT status FROM provider_model WHERE id=#{providerModelId}")
    String findProviderModelStatus(@Param("providerModelId") long providerModelId);

    @Select("SELECT status FROM project WHERE id=#{projectId} AND org_id=#{organizationId}")
    String findProjectStatus(@Param("projectId") long projectId, @Param("organizationId") long organizationId);

    @Select("SELECT id FROM service_identity WHERE org_id=#{organizationId} AND code='AICOSTOPS_ADVISOR' LIMIT 1")
    Long findAdvisorIdentity(@Param("organizationId") long organizationId);

    @Insert("INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at) VALUES(#{organizationId},'AICOSTOPS_ADVISOR','AI CostOps Advisor','ACTIVE',#{now},#{now}) ON DUPLICATE KEY UPDATE updated_at=#{now}")
    int ensureAdvisorIdentity(@Param("organizationId") long organizationId, @Param("now") Instant now);

    @Select("SELECT id FROM gateway_credential WHERE org_id=#{organizationId} AND service_identity_id=#{serviceIdentityId} AND credential_origin='INTERNAL_SYSTEM' AND status='ACTIVE' LIMIT 1")
    Long findInternalCredential(@Param("organizationId") long organizationId, @Param("serviceIdentityId") long serviceIdentityId);

    @Insert("""
            INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,
              principal_type,credential_origin,organization_member_id,service_identity_id,project_id,
              financial_scope_type,financial_scope_id,budget_enforcement_mode,status,expires_at,
              predecessor_credential_id,created_at,updated_at,revoked_at)
            VALUES(#{organizationId},#{prefix},#{digest},1,'SERVICE','INTERNAL_SYSTEM',NULL,
              #{serviceIdentityId},#{projectId},'PROJECT',#{projectId},#{budgetMode},'ACTIVE',NULL,
              NULL,#{now},#{now},NULL)
            """)
    int insertInternalCredential(@Param("organizationId") long organizationId, @Param("prefix") String prefix,
            @Param("digest") byte[] digest, @Param("serviceIdentityId") long serviceIdentityId,
            @Param("projectId") long projectId, @Param("budgetMode") String budgetMode, @Param("now") Instant now);

    @Insert("""
            INSERT IGNORE INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
            VALUES(#{credentialId},#{organizationId},#{modelId},'ACTIVE',#{now})
            """)
    int allowCredentialModel(@Param("credentialId") long credentialId, @Param("organizationId") long organizationId,
            @Param("modelId") long modelId, @Param("now") Instant now);

    @Select("SELECT model_id FROM provider_model WHERE id=#{providerModelId}")
    Long findLogicalModelOf(@Param("providerModelId") long providerModelId);

    @Insert("""
            INSERT INTO advisor_inference_job(org_id,requested_by,subject_type,subject_id,
              evidence_fingerprint,evidence_refs_json,advisor_profile_version,status,claim_token,claim_expires_at,
              gateway_request_id,attempt_count,failure_code,created_at,started_at,completed_at)
            VALUES(#{organizationId},#{requestedBy},#{subjectType},#{subjectId},#{fingerprint},
              CAST(#{refsJson} AS JSON),#{profileVersion},'PENDING',NULL,NULL,NULL,1,NULL,#{now},NULL,NULL)
            """)
    int insertJob(@Param("organizationId") long organizationId, @Param("requestedBy") long requestedBy,
            @Param("subjectType") String subjectType, @Param("subjectId") long subjectId,
            @Param("fingerprint") String fingerprint, @Param("refsJson") String refsJson,
            @Param("profileVersion") int profileVersion, @Param("now") Instant now);

    @Select("SELECT CAST(evidence_refs_json AS CHAR) FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}")
    String findEvidenceRefs(@Param("id") long id, @Param("organizationId") long organizationId);

    @Insert("INSERT INTO advisor_inference_attempt(org_id,job_id,attempt_no,gateway_request_id,status,failure_code,created_at) VALUES(#{organizationId},#{jobId},#{attemptNo},NULL,'PENDING',NULL,#{now})")
    int insertAttempt(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo, @Param("now") Instant now);

    @Select("SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,advisor_profile_version,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,failure_code,created_at,started_at,completed_at FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}")
    JobRow findJob(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,advisor_profile_version,
              status,claim_token,claim_expires_at,gateway_request_id,attempt_count,failure_code,
              created_at,started_at,completed_at
            FROM advisor_inference_job
            WHERE status='PENDING'
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

    @Update("UPDATE advisor_inference_job SET status='COMPLETED',completed_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND claim_token=#{token} AND status IN ('CLAIMED','DISPATCHING','RUNNING')")
    int markCompleted(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("token") String token, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='FAILED',failure_code=#{failureCode},completed_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND claim_token=#{token} AND status IN ('PENDING','CLAIMED','DISPATCHING','RUNNING')")
    int markFailed(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("token") String token, @Param("failureCode") String failureCode, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='PENDING',claim_token=NULL,claim_expires_at=NULL,attempt_count=attempt_count+1 WHERE id=#{id} AND org_id=#{organizationId} AND status IN ('COMPLETED','FAILED')")
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

    record ProfileRow(long id, long orgId, int version, long providerModelId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status, long createdBy, Instant createdAt, Instant updatedAt) {
    }

    record JobRow(long id, long orgId, long requestedBy, String subjectType, long subjectId,
            String evidenceFingerprint, int advisorProfileVersion, String status, String claimToken,
            Instant claimExpiresAt, Long gatewayRequestId, int attemptCount, String failureCode,
            Instant createdAt, Instant startedAt, Instant completedAt) {
    }

    record AttemptRow(long id, long orgId, long jobId, int attemptNo, Long gatewayRequestId,
            String status, String failureCode, Instant createdAt) {
    }

    record ExplanationRow(long id, long orgId, long jobId, int attemptNo, int schemaVersion,
            String summary, String driversExplanation, String recommendedActionsJson,
            String warningsJson, String factReferenceIdsJson, Instant createdAt) {
    }
}
