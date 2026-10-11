package com.aicostops.intelligence.application;

import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import java.time.Instant;
import java.time.LocalDate;

/**
 * HTTP read models for the frozen Cost Intelligence contract (M18 V3).
 *
 * <p>These records keep the exact frozen field names, order and types: money stays a decimal
 * string, {@code driversJson} stays a string, {@code robustZScore} stays a double and identifiers
 * stay JSON numbers with their original null semantics. They live in {@code application} so the
 * query/command services never depend on the {@code api} package that renders them; assembly is a
 * single {@code from(...)} per record so queries and commands cannot diverge.
 */
public final class CostIntelligenceReadModels {

    private CostIntelligenceReadModels() {
    }

    public record SummaryResponse(Long runId, String status, int anomalyCount, int forecastCount,
            int openRecommendations) {
    }

    public record AnomalyResponse(long id, String grainType, String grainKey, String currency,
            String observedAmount, String baselineAmount, String deltaAmount, String deltaPercent,
            double robustZScore, String driversJson) {

        public static AnomalyResponse from(IntelligenceMapper.AnomalyRow row) {
            return new AnomalyResponse(row.id(), row.grainType(), row.grainKey(), row.currency(),
                    row.observedAmount().toPlainString(), row.baselineAmount().toPlainString(),
                    row.deltaAmount().toPlainString(), row.deltaPercent().toPlainString(),
                    row.robustZScore(), row.driversJson());
        }
    }

    public record ForecastResponse(String scopeType, String scopeKey, String currency,
            String projectedAmount, String method, int historyBucketCount, String confidence,
            LocalDate observedThrough) {

        public static ForecastResponse from(IntelligenceMapper.ForecastRow row) {
            return new ForecastResponse(row.scopeType(), row.scopeKey(), row.currency(),
                    row.projectedAmount().toPlainString(), row.method(), row.historyBucketCount(),
                    row.confidence(), row.observedThrough());
        }
    }

    public record BudgetRiskResponse(String immediateExposure, String projectedPeriodEnd,
            String budgetTotal, String currency, String risk, boolean forecastAvailable) {
    }

    public record RecommendationResponse(long id, long logicalModelId, long currentProviderAccountId,
            long candidateProviderAccountId, long candidateProviderModelId, String currency,
            String currentCost, String candidateCost, String potentialSaving,
            String potentialSavingPercent, String status, Long routingPolicyId,
            boolean routingChangeRequired, Instant calculatedAt) {

        public static RecommendationResponse from(IntelligenceMapper.RecommendationRow row) {
            return new RecommendationResponse(row.id(), row.logicalModelId(),
                    row.currentProviderAccountId(), row.candidateProviderAccountId(),
                    row.candidateProviderModelId(), row.currency(), row.currentCost().toPlainString(),
                    row.candidateCost().toPlainString(), row.potentialSaving().toPlainString(),
                    row.potentialSavingPercent().toPlainString(), row.status(), row.routingPolicyId(),
                    row.routingChangeRequired(), row.calculatedAt());
        }
    }
}
