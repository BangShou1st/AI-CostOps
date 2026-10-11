package com.aicostops.advisor.infrastructure;

import java.time.Instant;

public final class AdvisorRows {
    private AdvisorRows() {}

    public record AnomalySubject(long id, long orgId, long runId, String grainType, String grainKey,
            String currency, java.math.BigDecimal observedAmount, java.math.BigDecimal baselineAmount,
            java.math.BigDecimal deltaAmount, String driversJson) {
    }

    public record SnapshotRow(long id, long orgId, long jobId, int schemaVersion, String subjectType,
            long subjectId, String currency, String factsJson, String driversJson, String summaryJson,
            String evidenceFingerprint, Instant generatedAt, Instant createdAt) {
    }

    public record ForecastSubject(long id, long orgId, String scopeType, String scopeKey, String currency,
            java.math.BigDecimal projectedAmount, String method) {
    }

    public record SavingsSubject(long id, long orgId, long logicalModelId, String currency,
            java.math.BigDecimal currentCost, java.math.BigDecimal candidateCost,
            java.math.BigDecimal potentialSaving) {
    }

    public record BudgetSubject(long id, long orgId, String scopeType, long scopeId, String currency,
            java.math.BigDecimal totalAmount, java.math.BigDecimal actualAmount,
            java.math.BigDecimal committedAmount) {
    }

    public record ProfileRow(long id, long orgId, int version, long providerModelId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status, long createdBy, Instant createdAt, Instant updatedAt) {
    }

    // requestedByUserId is app_user.id (human requester), never organization_member.id.
    public record JobRow(long id, long orgId, long requestedByUserId, String subjectType, long subjectId,
            String evidenceFingerprint, int advisorProfileVersion, Long advisorProfileId, String status,
            String claimToken, Instant claimExpiresAt, Long gatewayRequestId, int attemptCount,
            String failureCode, Instant createdAt, Instant startedAt, Instant completedAt) {
    }

    public record AttemptRow(long id, long orgId, long jobId, int attemptNo, Long gatewayRequestId,
            String status, String failureCode, Instant createdAt) {
    }

    public record ExplanationRow(long id, long orgId, long jobId, int attemptNo, int schemaVersion,
            String summary, String driversExplanation, String recommendedActionsJson,
            String warningsJson, String factReferenceIdsJson, Instant createdAt) {
    }
}
