package com.aicostops.intelligence.api;

import com.aicostops.intelligence.application.CostIntelligenceService;
import com.aicostops.intelligence.application.CostIntelligenceService.AnomalyResponse;
import com.aicostops.intelligence.application.CostIntelligenceService.BudgetRiskResponse;
import com.aicostops.intelligence.application.CostIntelligenceService.ForecastResponse;
import com.aicostops.intelligence.application.CostIntelligenceService.RecommendationResponse;
import com.aicostops.intelligence.application.CostIntelligenceService.SummaryResponse;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cost-intelligence")
public class CostIntelligenceController {

    private final CostIntelligenceService intelligence;

    public CostIntelligenceController(CostIntelligenceService intelligence) {
        this.intelligence = intelligence;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return intelligence.summary(user, currency);
    }

    @GetMapping("/anomalies")
    public List<AnomalyResponse> anomalies(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return intelligence.anomalies(user, currency);
    }

    @GetMapping("/forecasts")
    public List<ForecastResponse> forecasts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return intelligence.forecasts(user, currency);
    }

    @GetMapping("/budget-risks")
    public BudgetRiskResponse budgetRisk(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "PROJECT") String scopeType,
            @RequestParam long scopeId,
            @RequestParam(defaultValue = "USD") String currency) {
        return intelligence.budgetRisk(user, scopeType, scopeId, currency);
    }

    @GetMapping("/recommendations")
    public List<RecommendationResponse> recommendations(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "USD") String currency) {
        return intelligence.recommendations(user, currency);
    }

    @PostMapping("/recommendations/{id}/acknowledge")
    public RecommendationResponse acknowledge(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return intelligence.acknowledge(user, id);
    }

    @PostMapping("/recommendations/{id}/dismiss")
    public RecommendationResponse dismiss(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return intelligence.dismiss(user, id);
    }

    @PostMapping("/recommendations/{id}/mark-applied")
    public RecommendationResponse markApplied(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestParam(required = false) Long routingPolicyId) {
        return intelligence.markApplied(user, id, routingPolicyId);
    }
}
