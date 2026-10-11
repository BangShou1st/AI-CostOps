package com.aicostops.advisor.application;

import java.util.List;

public final class AdvisorRequests {
    private AdvisorRequests() {}

    public record UpdateProfileRequest(
            long providerModelId, long projectId, String financialScopeType,
            long financialScopeId, String budgetEnforcementMode) {
    }

    public record ExplanationRequest(String subjectType, long subjectId) {
        public ExplanationRequest(String subjectType, long subjectId, String currency,
                List<AdvisorEvidence.MoneyFact> facts, List<AdvisorEvidence.Driver> drivers,
                String forecastSummary, String budgetRiskSummary, String savingsSummary) {
            this(subjectType, subjectId);
        }
    }

}
