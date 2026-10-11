package com.aicostops.advisor.application;

import java.util.List;

public final class AdvisorReadModels {
    private AdvisorReadModels() {}

    public record ProfileResponse(
            long id, int version, long providerModelId, long projectId,
            String financialScopeType, long financialScopeId, String budgetEnforcementMode,
            String status) {
    }

    public record JobResponse(
            long id, String subjectType, long subjectId, String status, int attemptCount,
            Long gatewayRequestId, String failureCode, List<AttemptResponse> attempts,
            ExplanationResponse explanation) {
    }

    public record AttemptResponse(int attemptNo, Long gatewayRequestId, String status, String failureCode) {
    }

    public record ExplanationResponse(String summary, String driversExplanation, int attemptNo) {
    }

}
