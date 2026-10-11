package com.aicostops.intelligence.api;

import com.aicostops.intelligence.application.BudgetRiskQueryService;
import com.aicostops.intelligence.application.CostIntelligenceQueryService;
import com.aicostops.intelligence.application.CostIntelligenceReadModels.AnomalyResponse;
import com.aicostops.intelligence.application.CostIntelligenceReadModels.BudgetRiskResponse;
import com.aicostops.intelligence.application.CostIntelligenceReadModels.ForecastResponse;
import com.aicostops.intelligence.application.CostIntelligenceReadModels.RecommendationResponse;
import com.aicostops.intelligence.application.CostIntelligenceReadModels.SummaryResponse;
import com.aicostops.intelligence.application.SavingsRecommendationCommandService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Frozen Cost Intelligence HTTP surface (M18 V3): eight paths over three application services —
 * reads, budget-risk projection, and recommendation commands. Paths, parameters, defaults and
 * permissions are unchanged by the service split; each service still authorizes before any
 * lookup.
 */
@RestController
@RequestMapping("/api/v1/cost-intelligence")
public class CostIntelligenceController {

    private final CostIntelligenceQueryService queries;
    private final BudgetRiskQueryService budgetRisks;
    private final SavingsRecommendationCommandService commands;

    public CostIntelligenceController(CostIntelligenceQueryService queries,
            BudgetRiskQueryService budgetRisks, SavingsRecommendationCommandService commands) {
        this.queries = queries;
        this.budgetRisks = budgetRisks;
        this.commands = commands;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return queries.summary(user, currency);
    }

    @GetMapping("/anomalies")
    public List<AnomalyResponse> anomalies(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return queries.anomalies(user, currency);
    }

    @GetMapping("/forecasts")
    public List<ForecastResponse> forecasts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return queries.forecasts(user, currency);
    }

    @GetMapping("/budget-risks")
    public BudgetRiskResponse budgetRisk(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "PROJECT") String scopeType,
            @RequestParam long scopeId,
            @RequestParam(defaultValue = "USD") String currency) {
        return budgetRisks.budgetRisk(user, scopeType, scopeId, currency);
    }

    @GetMapping("/recommendations")
    public List<RecommendationResponse> recommendations(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return queries.recommendations(user, currency);
    }

    @PostMapping("/recommendations/{id}/acknowledge")
    public RecommendationResponse acknowledge(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return commands.acknowledge(user, id);
    }

    @PostMapping("/recommendations/{id}/dismiss")
    public RecommendationResponse dismiss(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return commands.dismiss(user, id);
    }

    @PostMapping("/recommendations/{id}/mark-applied")
    public RecommendationResponse markApplied(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestParam(required = false) Long routingPolicyId) {
        return commands.markApplied(user, id, routingPolicyId);
    }
}
