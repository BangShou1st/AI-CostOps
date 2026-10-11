package com.aicostops.advisor.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.aicostops.advisor.infrastructure.AdvisorRows.AnomalySubject;
import com.aicostops.advisor.infrastructure.AdvisorRows.ForecastSubject;
import com.aicostops.advisor.infrastructure.AdvisorRows.SavingsSubject;
import com.aicostops.advisor.infrastructure.AdvisorRows.BudgetSubject;

@Mapper
public interface AdvisorEvidenceMapper {
    @Select("SELECT id,org_id,run_id,grain_type,grain_key,currency,observed_amount,baseline_amount,delta_amount,CAST(drivers_json AS CHAR) AS drivers_json FROM cost_anomaly WHERE id=#{id} AND org_id=#{organizationId}")
    AnomalySubject findAnomalySubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,scope_type,scope_key,currency,projected_amount,method FROM cost_forecast_snapshot WHERE id=#{id} AND org_id=#{organizationId}")
    ForecastSubject findForecastSubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,logical_model_id,currency,current_cost,candidate_cost,potential_saving FROM savings_recommendation WHERE id=#{id} AND org_id=#{organizationId}")
    SavingsSubject findSavingsSubject(@Param("id") long id, @Param("organizationId") long organizationId);

    @Select("SELECT id,org_id,scope_type,scope_id,currency,total_amount,actual_amount,committed_amount FROM budget WHERE id=#{id} AND org_id=#{organizationId} AND status='ACTIVE'")
    BudgetSubject findBudgetSubject(@Param("id") long id, @Param("organizationId") long organizationId);

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
}
