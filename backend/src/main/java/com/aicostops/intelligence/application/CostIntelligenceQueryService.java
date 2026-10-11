package com.aicostops.intelligence.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Organization-scoped Cost Intelligence reads (M18 V3).
 *
 * <p>Every entry point requires organization-level COST_READ before any lookup and reads exactly
 * yesterday's version-1 run. "No run yet" is a first-class answer: summary reports STALE and the
 * lists stay empty — never a silent fallback to the most recent successful run.
 */
@Service
public class CostIntelligenceQueryService {

    private final AuthorizationContextService authorizationContexts;
    private final IntelligenceMapper store;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public CostIntelligenceQueryService(AuthorizationContextService authorizationContexts,
            IntelligenceMapper store, Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.store = store;
        this.clock = clock;
    }

    public CostIntelligenceReadModels.SummaryResponse summary(AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var validated = CostIntelligenceValidation.validatedCurrency(currency);
        var runId = store.findRunId(context.organizationId(), runDate, validated,
                CostIntelligenceValidation.RUN_VERSION);
        if (runId == null) {
            return new CostIntelligenceReadModels.SummaryResponse(null, "STALE", 0, 0, 0);
        }
        var status = store.findRunStatus(context.organizationId(), runDate, validated,
                CostIntelligenceValidation.RUN_VERSION);
        return new CostIntelligenceReadModels.SummaryResponse(runId, status,
                store.listAnomalies(context.organizationId(), runId).size(),
                store.listForecasts(context.organizationId(), runId).size(),
                (int) store.listRecommendations(context.organizationId(), runId).stream()
                        .filter(r -> "OPEN".equals(r.status())).count());
    }

    public List<CostIntelligenceReadModels.AnomalyResponse> anomalies(
            AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate,
                CostIntelligenceValidation.validatedCurrency(currency),
                CostIntelligenceValidation.RUN_VERSION);
        if (runId == null) return List.of();
        return store.listAnomalies(context.organizationId(), runId).stream()
                .map(CostIntelligenceReadModels.AnomalyResponse::from)
                .toList();
    }

    public List<CostIntelligenceReadModels.ForecastResponse> forecasts(
            AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate,
                CostIntelligenceValidation.validatedCurrency(currency),
                CostIntelligenceValidation.RUN_VERSION);
        if (runId == null) return List.of();
        return store.listForecasts(context.organizationId(), runId).stream()
                .map(CostIntelligenceReadModels.ForecastResponse::from)
                .toList();
    }

    public List<CostIntelligenceReadModels.RecommendationResponse> recommendations(
            AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate,
                CostIntelligenceValidation.validatedCurrency(currency),
                CostIntelligenceValidation.RUN_VERSION);
        if (runId == null) return List.of();
        return store.listRecommendations(context.organizationId(), runId).stream()
                .map(CostIntelligenceReadModels.RecommendationResponse::from).toList();
    }
}
