package com.aicostops.gateway.advisor;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Gateway-side advisor job seam (M18 V3). The gateway claims durable jobs
 * and executes them through the normal governed dispatch path — budget
 * admission, reservation, usage, settlement and Ledger all apply.
 */
@Mapper
public interface AdvisorJobMapper {

    @Select("""
            SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,
              CAST(evidence_refs_json AS CHAR) AS evidence_refs_json,advisor_profile_version,
              advisor_profile_id,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,
              failure_code,created_at,started_at,completed_at
            FROM advisor_inference_job
            WHERE (status='PENDING' AND gateway_request_id IS NULL)
              OR (status='CLAIMED' AND claim_expires_at < #{now} AND gateway_request_id IS NULL)
            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    JobRow claimEligibleAny(@Param("now") Instant now);

    @Select("""
            SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,
              CAST(evidence_refs_json AS CHAR) AS evidence_refs_json,advisor_profile_version,
              advisor_profile_id,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,
              failure_code,created_at,started_at,completed_at
            FROM advisor_inference_job WHERE id=#{id} AND org_id=#{organizationId}
            """)
    JobRow findJob(@Param("id") long id, @Param("organizationId") long organizationId);

    @Update("UPDATE advisor_inference_job SET status='CLAIMED',claim_token=#{token},claim_expires_at=#{lease},started_at=#{now} WHERE id=#{id} AND ((status='PENDING') OR (status='CLAIMED' AND claim_expires_at < #{now} AND gateway_request_id IS NULL))")
    int markClaimed(@Param("id") long id, @Param("token") String token,
            @Param("lease") Instant lease, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status=#{status},gateway_request_id=#{gatewayRequestId},claim_expires_at=#{lease} WHERE id=#{id} AND claim_token=#{token} AND status IN ('CLAIMED','DISPATCHING','RUNNING')")
    int linkGateway(@Param("id") long id, @Param("token") String token,
            @Param("status") String status, @Param("gatewayRequestId") long gatewayRequestId,
            @Param("lease") Instant lease);

    @Update("UPDATE advisor_inference_attempt SET gateway_request_id=#{gatewayRequestId},status='RUNNING' WHERE job_id=#{jobId} AND attempt_no=#{attemptNo}")
    int linkAttempt(@Param("jobId") long jobId, @Param("attemptNo") int attemptNo,
            @Param("gatewayRequestId") long gatewayRequestId);

    @Update("UPDATE advisor_inference_attempt SET status='COMPLETED' WHERE job_id=#{jobId} AND attempt_no=#{attemptNo} AND status IN ('PENDING','RUNNING')")
    int completeAttempt(@Param("jobId") long jobId, @Param("attemptNo") int attemptNo);

    @Update("UPDATE advisor_inference_attempt SET status='FAILED',failure_code=#{failureCode} WHERE job_id=#{jobId} AND attempt_no=#{attemptNo} AND status IN ('PENDING','RUNNING')")
    int failAttempt(@Param("jobId") long jobId, @Param("attemptNo") int attemptNo,
            @Param("failureCode") String failureCode);

    @Update("UPDATE advisor_inference_job SET status='COMPLETED',completed_at=#{now} WHERE id=#{id} AND claim_token=#{token} AND status IN ('CLAIMED','DISPATCHING','RUNNING')")
    int markCompleted(@Param("id") long id, @Param("token") String token, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='FAILED',failure_code=#{failureCode},completed_at=#{now} WHERE id=#{id} AND claim_token=#{token} AND status IN ('PENDING','CLAIMED','DISPATCHING','RUNNING')")
    int markFailed(@Param("id") long id, @Param("token") String token,
            @Param("failureCode") String failureCode, @Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET status='PENDING',claim_token=NULL,claim_expires_at=NULL WHERE status='CLAIMED' AND claim_expires_at < #{now} AND gateway_request_id IS NULL")
    int reclaimOrphans(@Param("now") Instant now);

    @Select("""
            SELECT id,org_id,requested_by,subject_type,subject_id,evidence_fingerprint,
              CAST(evidence_refs_json AS CHAR) AS evidence_refs_json,advisor_profile_version,
              advisor_profile_id,status,claim_token,claim_expires_at,gateway_request_id,attempt_count,
              failure_code,created_at,started_at,completed_at
            FROM advisor_inference_job
            WHERE status IN ('CLAIMED','DISPATCHING','RUNNING')
              AND claim_expires_at < #{now} AND gateway_request_id IS NOT NULL
            """)
    java.util.List<JobRow> stuckLinked(@Param("now") Instant now);

    @Update("UPDATE advisor_inference_job SET claim_expires_at=#{lease} WHERE id=#{id} AND status IN ('CLAIMED','DISPATCHING','RUNNING')")
    int extendLease(@Param("id") long id, @Param("lease") Instant lease);

    @Select("SELECT state FROM gateway_request WHERE id=#{requestId}")
    String findGatewayRequestState(@Param("requestId") long requestId);

    @Select("""
            SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status
            FROM advisor_profile WHERE org_id=#{organizationId} AND status='ACTIVE' LIMIT 1
            """)
    ProfileRow findActiveProfile(@Param("organizationId") long organizationId);

    @Select("""
            SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status
            FROM advisor_profile WHERE id=#{id} AND org_id=#{organizationId}
            """)
    ProfileRow findProfileById(@Param("organizationId") long organizationId, @Param("id") long id);

    @Select("""
            SELECT id,org_id,version,provider_model_id,project_id,financial_scope_type,
              financial_scope_id,budget_enforcement_mode,status
            FROM advisor_profile WHERE org_id=#{organizationId} AND version=#{version} LIMIT 1
            """)
    ProfileRow findProfileByOrgVersion(@Param("organizationId") long organizationId,
            @Param("version") int version);

    /** Exact credential bound to one profile revision (frozen job semantics, any profile status). */
    @Select("""
            SELECT gc.id,gc.org_id,gc.service_identity_id,gc.project_id,gc.financial_scope_type,
              gc.financial_scope_id,gc.budget_enforcement_mode
            FROM gateway_credential gc
            WHERE gc.org_id=#{organizationId} AND gc.advisor_profile_id=#{profileId}
              AND gc.credential_origin='INTERNAL_SYSTEM' AND gc.status='ACTIVE' LIMIT 1
            """)
    InternalCredentialRow findCredentialForProfile(@Param("organizationId") long organizationId,
            @Param("profileId") long profileId);

    @Select("SELECT id,org_id,job_id,schema_version,subject_type,subject_id,currency," +
            "CAST(facts_json AS CHAR) AS facts_json,CAST(drivers_json AS CHAR) AS drivers_json," +
            "CAST(summary_json AS CHAR) AS summary_json,evidence_fingerprint,generated_at,created_at" +
            " FROM advisor_evidence_snapshot WHERE job_id=#{jobId} AND org_id=#{organizationId}")
    SnapshotRow findEvidenceSnapshot(@Param("jobId") long jobId,
            @Param("organizationId") long organizationId);

    @Select("SELECT model_id FROM provider_model WHERE id=#{providerModelId}")
    Long findLogicalModelOf(@Param("providerModelId") long providerModelId);

    @Select("""
            SELECT gc.id,gc.org_id,gc.service_identity_id,gc.project_id,gc.financial_scope_type,
              gc.financial_scope_id,gc.budget_enforcement_mode
            FROM gateway_credential gc JOIN service_identity si
              ON si.id=gc.service_identity_id AND si.org_id=gc.org_id
            WHERE gc.org_id=#{organizationId} AND si.code='AICOSTOPS_ADVISOR'
              AND gc.credential_origin='INTERNAL_SYSTEM' AND gc.status='ACTIVE' LIMIT 1
            """)
    InternalCredentialRow findInternalCredential(@Param("organizationId") long organizationId);

    /**
     * P0: exact ACTIVE-profile-bound credential. The worker must use this row; the legacy
     * LIMIT 1 lookup above is retained only as a migration fallback for pre-V26 rows.
     */
    @Select("""
            SELECT gc.id,gc.org_id,gc.service_identity_id,gc.project_id,gc.financial_scope_type,
              gc.financial_scope_id,gc.budget_enforcement_mode
            FROM gateway_credential gc
            JOIN service_identity si ON si.id = gc.service_identity_id AND si.org_id = gc.org_id
            JOIN advisor_profile ap ON ap.id = gc.advisor_profile_id AND ap.org_id = gc.org_id
            WHERE gc.org_id = #{organizationId} AND si.code = 'AICOSTOPS_ADVISOR'
              AND gc.credential_origin = 'INTERNAL_SYSTEM' AND gc.status = 'ACTIVE'
              AND ap.status = 'ACTIVE' LIMIT 1
            """)
    InternalCredentialRow findBoundInternalCredential(@Param("organizationId") long organizationId);

    @Select("""
            SELECT grain_type,grain_key,currency,observed_amount,baseline_amount,delta_amount,
              delta_percent,robust_z_score,CAST(drivers_json AS CHAR) AS drivers_json
            FROM cost_anomaly WHERE org_id=#{organizationId} AND id=#{id}
            """)
    AnomalySubject findAnomaly(@Param("organizationId") long organizationId, @Param("id") long id);

    @Select("""
            SELECT scope_type,scope_key,currency,projected_amount,method,history_bucket_count,
              confidence,observed_through
            FROM cost_forecast_snapshot WHERE org_id=#{organizationId} AND id=#{id}
            """)
    ForecastSubject findForecast(@Param("organizationId") long organizationId, @Param("id") long id);

    @Select("""
            SELECT logical_model_id,currency,evidence_window_start,evidence_window_end,
              current_cost,candidate_cost,potential_saving,potential_saving_percent,
              current_provider_account_id,candidate_provider_account_id
            FROM savings_recommendation WHERE org_id=#{organizationId} AND id=#{id}
            """)
    SavingsSubject findSavings(@Param("organizationId") long organizationId, @Param("id") long id);

    @Select("""
            SELECT b.total_amount,b.actual_amount,b.committed_amount,b.currency,
              COALESCE((SELECT SUM(reserved_amount) FROM budget_reservation
                WHERE org_id=b.org_id AND budget_id=b.id AND status IN ('ACTIVE','PENDING_HOLD')),0) AS reserved_amount
            FROM budget b JOIN billing_period bp ON bp.id=b.billing_period_id AND bp.org_id=b.org_id
            WHERE b.org_id=#{organizationId} AND b.scope_type=#{scopeType} AND b.scope_id=#{scopeId}
              AND b.status='ACTIVE' AND bp.status='OPEN' ORDER BY b.id DESC LIMIT 1
            """)
    BudgetSubject findOpenBudget(@Param("organizationId") long organizationId,
            @Param("scopeType") String scopeType, @Param("scopeId") long scopeId);

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

    record JobRow(long id, long orgId, long requestedBy, String subjectType, long subjectId,
            String evidenceFingerprint, String evidenceRefsJson, int advisorProfileVersion,
            Long advisorProfileId, String status, String claimToken, Instant claimExpiresAt,
            Long gatewayRequestId, int attemptCount, String failureCode, Instant createdAt,
            Instant startedAt, Instant completedAt) {
    }

    record SnapshotRow(long id, long orgId, long jobId, int schemaVersion, String subjectType,
            long subjectId, String currency, String factsJson, String driversJson, String summaryJson,
            String evidenceFingerprint, Instant generatedAt, Instant createdAt) {
    }

    record ProfileRow(long id, long orgId, int version, long providerModelId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status) {
    }

    record InternalCredentialRow(long id, long orgId, long serviceIdentityId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode) {
    }

    record AnomalySubject(String grainType, String grainKey, String currency,
            java.math.BigDecimal observedAmount, java.math.BigDecimal baselineAmount,
            java.math.BigDecimal deltaAmount, java.math.BigDecimal deltaPercent,
            double robustZScore, String driversJson) {
    }

    record ForecastSubject(String scopeType, String scopeKey, String currency,
            java.math.BigDecimal projectedAmount, String method, int historyBucketCount,
            String confidence, java.time.LocalDate observedThrough) {
    }

    record SavingsSubject(long logicalModelId, String currency, java.time.LocalDate evidenceWindowStart,
            java.time.LocalDate evidenceWindowEnd, java.math.BigDecimal currentCost,
            java.math.BigDecimal candidateCost, java.math.BigDecimal potentialSaving,
            java.math.BigDecimal potentialSavingPercent, long currentProviderAccountId,
            long candidateProviderAccountId) {
    }

    record BudgetSubject(java.math.BigDecimal totalAmount, java.math.BigDecimal actualAmount,
            java.math.BigDecimal committedAmount, String currency, java.math.BigDecimal reservedAmount) {
    }

    @Select("""
            SELECT b.total_amount,b.actual_amount,b.committed_amount,b.currency,
              COALESCE((SELECT SUM(reserved_amount) FROM budget_reservation
                WHERE org_id=b.org_id AND budget_id=b.id AND status IN ('ACTIVE','PENDING_HOLD')),0) AS reserved_amount
            FROM budget b WHERE b.org_id=#{organizationId} AND b.id=#{budgetId}
            """)
    BudgetSubject findBudget(@Param("organizationId") long organizationId, @Param("budgetId") long budgetId);
}
