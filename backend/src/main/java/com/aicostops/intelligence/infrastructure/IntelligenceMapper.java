package com.aicostops.intelligence.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IntelligenceMapper {

    @Insert("INSERT IGNORE INTO cost_intelligence_run(org_id,analysis_date,currency,run_version,status,started_at,completed_at,failure_code,created_at) VALUES(#{organizationId},#{date},#{currency},#{version},'PENDING',NULL,NULL,NULL,#{now})")
    int insertRunIfMissing(@Param("organizationId") long organizationId, @Param("date") LocalDate date,
            @Param("currency") String currency, @Param("version") int version, @Param("now") Instant now);

    @Update("UPDATE cost_intelligence_run SET status='RUNNING',started_at=#{now} WHERE org_id=#{organizationId} AND analysis_date=#{date} AND currency=#{currency} AND run_version=#{version} AND status='PENDING'")
    int claimRun(@Param("organizationId") long organizationId, @Param("date") LocalDate date,
            @Param("currency") String currency, @Param("version") int version, @Param("now") Instant now);

    @Update("UPDATE cost_intelligence_run SET status=#{status},completed_at=#{now},failure_code=#{failureCode} WHERE org_id=#{organizationId} AND analysis_date=#{date} AND currency=#{currency} AND run_version=#{version} AND status='RUNNING'")
    int finishRun(@Param("organizationId") long organizationId, @Param("date") LocalDate date,
            @Param("currency") String currency, @Param("version") int version,
            @Param("status") String status, @Param("failureCode") String failureCode, @Param("now") Instant now);

    @Select("SELECT id FROM cost_intelligence_run WHERE org_id=#{organizationId} AND analysis_date=#{date} AND currency=#{currency} AND run_version=#{version}")
    Long findRunId(@Param("organizationId") long organizationId, @Param("date") LocalDate date,
            @Param("currency") String currency, @Param("version") int version);

    @Select("SELECT status FROM cost_intelligence_run WHERE org_id=#{organizationId} AND analysis_date=#{date} AND currency=#{currency} AND run_version=#{version}")
    String findRunStatus(@Param("organizationId") long organizationId, @Param("date") LocalDate date,
            @Param("currency") String currency, @Param("version") int version);

    @Insert("""
            INSERT INTO cost_anomaly(org_id,run_id,grain_type,grain_key,currency,observed_amount,
              baseline_amount,delta_amount,delta_percent,robust_z_score,drivers_json,created_at)
            VALUES(#{organizationId},#{runId},#{grainType},#{grainKey},#{currency},#{observed},
              #{baseline},#{delta},#{deltaPercent},#{zScore},CAST(#{driversJson} AS JSON),#{now})
            """)
    int insertAnomaly(@Param("organizationId") long organizationId, @Param("runId") long runId,
            @Param("grainType") String grainType, @Param("grainKey") String grainKey,
            @Param("currency") String currency, @Param("observed") BigDecimal observed,
            @Param("baseline") BigDecimal baseline, @Param("delta") BigDecimal delta,
            @Param("deltaPercent") BigDecimal deltaPercent, @Param("zScore") double zScore,
            @Param("driversJson") String driversJson, @Param("now") Instant now);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("""
            SELECT id,grain_type,grain_key,currency,observed_amount,baseline_amount,delta_amount,
              delta_percent,robust_z_score,CAST(drivers_json AS CHAR) AS drivers_json
            FROM cost_anomaly WHERE org_id=#{organizationId} AND run_id=#{runId} ORDER BY id
            """)
    List<AnomalyRow> listAnomalies(@Param("organizationId") long organizationId, @Param("runId") long runId);

    @Insert("""
            INSERT INTO cost_forecast_snapshot(org_id,run_id,scope_type,scope_key,currency,
              projected_amount,method,history_bucket_count,confidence,observed_through,created_at)
            VALUES(#{organizationId},#{runId},#{scopeType},#{scopeKey},#{currency},
              #{projected},#{method},#{buckets},#{confidence},#{observedThrough},#{now})
            """)
    int insertForecast(@Param("organizationId") long organizationId, @Param("runId") long runId,
            @Param("scopeType") String scopeType, @Param("scopeKey") String scopeKey,
            @Param("currency") String currency, @Param("projected") BigDecimal projected,
            @Param("method") String method, @Param("buckets") int buckets,
            @Param("confidence") String confidence, @Param("observedThrough") LocalDate observedThrough,
            @Param("now") Instant now);

    @Select("SELECT scope_type,scope_key,currency,projected_amount,method,history_bucket_count,confidence,observed_through FROM cost_forecast_snapshot WHERE org_id=#{organizationId} AND run_id=#{runId} ORDER BY id")
    List<ForecastRow> listForecasts(@Param("organizationId") long organizationId, @Param("runId") long runId);

    @Insert("""
            INSERT INTO savings_recommendation(org_id,run_id,logical_model_id,current_provider_account_id,
              current_provider_model_id,current_pricing_version_id,candidate_provider_account_id,
              candidate_provider_model_id,candidate_pricing_version_id,currency,evidence_window_start,
              evidence_window_end,current_cost,candidate_cost,potential_saving,potential_saving_percent,
              evidence_fingerprint,status,routing_policy_id,calculated_at,created_at)
            VALUES(#{organizationId},#{runId},#{logicalModelId},#{currentAccount},#{currentModel},
              #{currentPricing},#{candidateAccount},#{candidateModel},#{candidatePricing},#{currency},
              #{windowStart},#{windowEnd},#{currentCost},#{candidateCost},#{saving},#{savingPercent},
              #{fingerprint},'OPEN',NULL,#{now},#{now})
            """)
    int insertRecommendation(@Param("organizationId") long organizationId, @Param("runId") long runId,
            @Param("logicalModelId") long logicalModelId, @Param("currentAccount") long currentAccount,
            @Param("currentModel") long currentModel, @Param("currentPricing") long currentPricing,
            @Param("candidateAccount") long candidateAccount, @Param("candidateModel") long candidateModel,
            @Param("candidatePricing") long candidatePricing, @Param("currency") String currency,
            @Param("windowStart") LocalDate windowStart, @Param("windowEnd") LocalDate windowEnd,
            @Param("currentCost") BigDecimal currentCost, @Param("candidateCost") BigDecimal candidateCost,
            @Param("saving") BigDecimal saving, @Param("savingPercent") BigDecimal savingPercent,
            @Param("fingerprint") String fingerprint, @Param("now") Instant now);

    @Select("""
            SELECT id,logical_model_id,current_provider_account_id,current_provider_model_id,
              current_pricing_version_id,candidate_provider_account_id,candidate_provider_model_id,
              candidate_pricing_version_id,currency,evidence_window_start,evidence_window_end,
              current_cost,candidate_cost,potential_saving,potential_saving_percent,
              evidence_fingerprint,status,routing_policy_id,calculated_at
            FROM savings_recommendation WHERE org_id=#{organizationId} AND run_id=#{runId} ORDER BY id
            """)
    List<RecommendationRow> listRecommendations(@Param("organizationId") long organizationId,
            @Param("runId") long runId);

    @Select("""
            SELECT id,logical_model_id,current_provider_account_id,current_provider_model_id,
              current_pricing_version_id,candidate_provider_account_id,candidate_provider_model_id,
              candidate_pricing_version_id,currency,evidence_window_start,evidence_window_end,
              current_cost,candidate_cost,potential_saving,potential_saving_percent,
              evidence_fingerprint,status,routing_policy_id,calculated_at
            FROM savings_recommendation WHERE id=#{id} AND org_id=#{organizationId}
            """)
    RecommendationRow findRecommendation(@Param("id") long id, @Param("organizationId") long organizationId);

    @Update("UPDATE savings_recommendation SET status=#{to},routing_policy_id=#{policyId} WHERE id=#{id} AND org_id=#{organizationId} AND status=#{from}")
    int transitionRecommendation(@Param("id") long id, @Param("organizationId") long organizationId,
            @Param("from") String from, @Param("to") String to, @Param("policyId") Long policyId);

    record AnomalyRow(long id, String grainType, String grainKey, String currency,
            BigDecimal observedAmount, BigDecimal baselineAmount, BigDecimal deltaAmount,
            BigDecimal deltaPercent, double robustZScore, String driversJson) {
    }

    record ForecastRow(String scopeType, String scopeKey, String currency, BigDecimal projectedAmount,
            String method, int historyBucketCount, String confidence, LocalDate observedThrough) {
    }

    record RecommendationRow(long id, long logicalModelId, long currentProviderAccountId,
            long currentProviderModelId, long currentPricingVersionId, long candidateProviderAccountId,
            long candidateProviderModelId, long candidatePricingVersionId, String currency,
            LocalDate evidenceWindowStart, LocalDate evidenceWindowEnd, BigDecimal currentCost,
            BigDecimal candidateCost, BigDecimal potentialSaving, BigDecimal potentialSavingPercent,
            String evidenceFingerprint, String status, Long routingPolicyId, Instant calculatedAt) {
    }
}
