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

    @Select("SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,created_by,created_at,updated_at FROM advisor_profile WHERE org_id=#{organizationId} AND version=#{version} LIMIT 1")
    ProfileRow findProfileByOrgVersion(@Param("organizationId") long organizationId,
            @Param("version") int version);

    /** Exact credential bound to one profile revision (no ACTIVE-profile join: frozen semantics). */
    @Select("""
            SELECT gc.id FROM gateway_credential gc
            WHERE gc.org_id = #{organizationId} AND gc.advisor_profile_id = #{profileId}
              AND gc.credential_origin = 'INTERNAL_SYSTEM' AND gc.status = 'ACTIVE' LIMIT 1
            """)
    Long findCredentialForProfile(@Param("organizationId") long organizationId,
            @Param("profileId") long profileId);

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

    /** Org-visible Provider Model status: global OR same-org private, ACTIVE, routable. */
    @Select("""
            SELECT pm.status FROM provider_model pm
            JOIN model_catalog mc ON mc.id = pm.model_id
            WHERE pm.id = #{providerModelId}
              AND (pm.owner_org_id IS NULL OR pm.owner_org_id = #{organizationId})
              AND (mc.owner_org_id IS NULL OR mc.owner_org_id = #{organizationId})
              AND (pm.provider_account_id IS NULL OR EXISTS(
                SELECT 1 FROM provider_account pa
                WHERE pa.id = pm.provider_account_id AND pa.org_id = #{organizationId} AND pa.status = 'ACTIVE'))
            """)
    String findOrgVisibleProviderModelStatus(@Param("providerModelId") long providerModelId,
            @Param("organizationId") long organizationId);

    @Select("SELECT status FROM project WHERE id=#{projectId} AND org_id=#{organizationId}")
    String findProjectStatus(@Param("projectId") long projectId, @Param("organizationId") long organizationId);

    @Select("SELECT status FROM team WHERE id=#{scopeId} AND org_id=#{organizationId}")
    String findTeamStatus(@Param("scopeId") long scopeId, @Param("organizationId") long organizationId);

    @Select("SELECT status FROM cost_center WHERE id=#{scopeId} AND org_id=#{organizationId}")
    String findCostCenterStatus(@Param("scopeId") long scopeId, @Param("organizationId") long organizationId);

    @Select("SELECT id FROM service_identity WHERE org_id=#{organizationId} AND code='AICOSTOPS_ADVISOR' LIMIT 1")
    Long findAdvisorIdentity(@Param("organizationId") long organizationId);

    @Insert("INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at) VALUES(#{organizationId},'AICOSTOPS_ADVISOR','AI CostOps Advisor','ACTIVE',#{now},#{now}) ON DUPLICATE KEY UPDATE updated_at=#{now}")
    int ensureAdvisorIdentity(@Param("organizationId") long organizationId, @Param("now") Instant now);

    @Select("SELECT id FROM gateway_credential WHERE org_id=#{organizationId} AND service_identity_id=#{serviceIdentityId} AND credential_origin='INTERNAL_SYSTEM' AND status='ACTIVE' LIMIT 1")
    Long findInternalCredential(@Param("organizationId") long organizationId, @Param("serviceIdentityId") long serviceIdentityId);

    /** Current ACTIVE profile-bound credential for the advisor identity (P0 exact binding). */
    @Select("""
            SELECT gc.id FROM gateway_credential gc
            JOIN advisor_profile ap ON ap.id = gc.advisor_profile_id AND ap.org_id = gc.org_id
            WHERE gc.org_id = #{organizationId} AND gc.service_identity_id = #{serviceIdentityId}
              AND gc.credential_origin = 'INTERNAL_SYSTEM' AND gc.status = 'ACTIVE'
              AND ap.status = 'ACTIVE' LIMIT 1
            """)
    Long findBoundInternalCredential(@Param("organizationId") long organizationId,
            @Param("serviceIdentityId") long serviceIdentityId);

    @Select("""
            SELECT gc.id FROM gateway_credential gc
            WHERE gc.org_id = #{organizationId} AND gc.service_identity_id = #{serviceIdentityId}
              AND gc.credential_origin = 'INTERNAL_SYSTEM' AND gc.status = 'ACTIVE'
            """)
    java.util.List<Long> listActiveInternalCredentials(@Param("organizationId") long organizationId,
            @Param("serviceIdentityId") long serviceIdentityId);

    @Update("UPDATE gateway_credential SET status='REVOKED', revoked_at=#{now}, updated_at=#{now} WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    int revokeInternalCredential(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("now") Instant now);

    @Update("UPDATE gateway_credential_model SET status='DISABLED' WHERE credential_id=#{credentialId} AND org_id=#{organizationId} AND status='ACTIVE'")
    int disableCredentialModels(@Param("credentialId") long credentialId,
            @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,secret_digest_version,
              principal_type,credential_origin,organization_member_id,service_identity_id,project_id,
              financial_scope_type,financial_scope_id,budget_enforcement_mode,status,expires_at,
              predecessor_credential_id,advisor_profile_id,created_at,updated_at,revoked_at)
            VALUES(#{organizationId},#{prefix},#{digest},1,'SERVICE','INTERNAL_SYSTEM',NULL,
              #{serviceIdentityId},#{projectId},#{scopeType},#{scopeId},#{budgetMode},'ACTIVE',NULL,
              #{predecessorId},#{profileId},#{now},#{now},NULL)
            """)
    int insertBoundInternalCredential(@Param("organizationId") long organizationId, @Param("prefix") String prefix,
            @Param("digest") byte[] digest, @Param("serviceIdentityId") long serviceIdentityId,
            @Param("projectId") long projectId, @Param("scopeType") String scopeType,
            @Param("scopeId") long scopeId, @Param("budgetMode") String budgetMode,
            @Param("predecessorId") Long predecessorId, @Param("profileId") long profileId,
            @Param("now") Instant now);

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

    @Select("SELECT id,org_id,run_id,grain_type,grain_key,currency,observed_amount,baseline_amount,delta_amount,CAST(drivers_json AS CHAR) AS drivers_json FROM cost_anomaly WHERE id=#{id} AND org_id=#{organizationId}")
    AnomalySubject findAnomalySubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,scope_type,scope_key,currency,projected_amount,method FROM cost_forecast_snapshot WHERE id=#{id} AND org_id=#{organizationId}")
    ForecastSubject findForecastSubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,logical_model_id,currency,current_cost,candidate_cost,potential_saving FROM savings_recommendation WHERE id=#{id} AND org_id=#{organizationId}")
    SavingsSubject findSavingsSubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,scope_type,scope_id,currency,total_amount,actual_amount,committed_amount FROM budget WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    BudgetSubject findBudgetSubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT model_id FROM provider_model WHERE id=#{providerModelId}")
    Long findLogicalModelOf(@Param("providerModelId") long providerModelId);

    /** Org-visible logical model for a provider model (global OR same-org private). */
    @Select("""
            SELECT pm.model_id FROM provider_model pm
            JOIN model_catalog mc ON mc.id = pm.model_id
            WHERE pm.id = #{providerModelId}
              AND (pm.owner_org_id IS NULL OR pm.owner_org_id = #{organizationId})
              AND (mc.owner_org_id IS NULL OR mc.owner_org_id = #{organizationId})
            """)
    Long findOrgVisibleLogicalModelOf(@Param("providerModelId") long providerModelId,
            @Param("organizationId") long organizationId);

    @Insert("""
            INSERT INTO advisor_inference_job(org_id,requested_by,subject_type,subject_id,
              evidence_fingerprint,evidence_refs_json,advisor_profile_version,advisor_profile_id,
              status,claim_token,claim_expires_at,
              gateway_request_id,attempt_count,failure_code,created_at,started_at,completed_at)
            VALUES(#{organizationId},#{requestedBy},#{subjectType},#{subjectId},#{fingerprint},
              CAST(#{refsJson} AS JSON),#{profileVersion},#{profileId},'PENDING',NULL,NULL,NULL,1,NULL,#{now},NULL,NULL)
            """)
    int insertJob(@Param("organizationId") long organizationId, @Param("requestedBy") long requestedBy,
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

    @Insert("""
            INSERT INTO advisor_evidence_snapshot(org_id,job_id,schema_version,subject_type,subject_id,
              currency,facts_json,drivers_json,summary_json,evidence_fingerprint,generated_at,created_at)
            VALUES(#{organizationId},#{jobId},#{schemaVersion},#{subjectType},#{subjectId},#{currency},
              CAST(#{factsJson} AS JSON),CAST(#{driversJson} AS JSON),CAST(#{summaryJson} AS JSON),
              #{fingerprint},#{generatedAt},#{now})
            """)
    int insertEvidenceSnapshot(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("schemaVersion") int schemaVersion, @Param("subjectType") String subjectType,
            @Param("subjectId") long subjectId, @Param("currency") String currency,
            @Param("factsJson") String factsJson, @Param("driversJson") String driversJson,
            @Param("summaryJson") String summaryJson, @Param("fingerprint") String fingerprint,
            @Param("generatedAt") Instant generatedAt, @Param("now") Instant now);

    @Select("SELECT id,org_id,job_id,schema_version,subject_type,subject_id,currency," +
            "CAST(facts_json AS CHAR) AS facts_json,CAST(drivers_json AS CHAR) AS drivers_json," +
            "CAST(summary_json AS CHAR) AS summary_json,evidence_fingerprint,generated_at,created_at" +
            " FROM advisor_evidence_snapshot WHERE job_id=#{jobId} AND org_id=#{organizationId}")
    SnapshotRow findEvidenceSnapshot(@Param("jobId") long jobId,
            @Param("organizationId") long organizationId);

    @Select("SELECT CAST(evidence_refs_json AS CHAR) FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}")
    String findEvidenceRefs(@Param("id") long id, @Param("organizationId") long organizationId);

    @Insert("INSERT INTO advisor_inference_attempt(org_id,job_id,attempt_no,gateway_request_id,status,failure_code,created_at) VALUES(#{organizationId},#{jobId},#{attemptNo},NULL,'PENDING',NULL,#{now})")
    int insertAttempt(@Param("organizationId") long organizationId, @Param("jobId") long jobId,
            @Param("attemptNo") int attemptNo, @Param("now") Instant now);

    @Select("SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,advisor_profile_version,advisor_profile_id,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,failure_code,created_at,started_at,completed_at FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}")
    JobRow findJob(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,advisor_profile_version,
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

    record AnomalySubject(long id, long orgId, long runId, String grainType, String grainKey,
            String currency, java.math.BigDecimal observedAmount, java.math.BigDecimal baselineAmount,
            java.math.BigDecimal deltaAmount, String driversJson) {
    }

    record SnapshotRow(long id, long orgId, long jobId, int schemaVersion, String subjectType,
            long subjectId, String currency, String factsJson, String driversJson, String summaryJson,
            String evidenceFingerprint, Instant generatedAt, Instant createdAt) {
    }

    record ForecastSubject(long id, long orgId, String scopeType, String scopeKey, String currency,
            java.math.BigDecimal projectedAmount, String method) {
    }

    record SavingsSubject(long id, long orgId, long logicalModelId, String currency,
            java.math.BigDecimal currentCost, java.math.BigDecimal candidateCost,
            java.math.BigDecimal potentialSaving) {
    }

    record BudgetSubject(long id, long orgId, String scopeType, long scopeId, String currency,
            java.math.BigDecimal totalAmount, java.math.BigDecimal actualAmount,
            java.math.BigDecimal committedAmount) {
    }

    record ProfileRow(long id, long orgId, int version, long providerModelId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status, long createdBy, Instant createdAt, Instant updatedAt) {
    }

    record JobRow(long id, long orgId, long requestedBy, String subjectType, long subjectId,
            String evidenceFingerprint, int advisorProfileVersion, Long advisorProfileId, String status,
            String claimToken, Instant claimExpiresAt, Long gatewayRequestId, int attemptCount,
            String failureCode, Instant createdAt, Instant startedAt, Instant completedAt) {
    }

    record AttemptRow(long id, long orgId, long jobId, int attemptNo, Long gatewayRequestId,
            String status, String failureCode, Instant createdAt) {
    }

    record ExplanationRow(long id, long orgId, long jobId, int attemptNo, int schemaVersion,
            String summary, String driversExplanation, String recommendedActionsJson,
            String warningsJson, String factReferenceIdsJson, Instant createdAt) {
    }
}
