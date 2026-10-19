package com.aicostops.intelligence.api;

import com.aicostops.intelligence.application.CostHistoryCoverageCommandService;
import com.aicostops.intelligence.application.CostHistoryCoverageQueryService;
import com.aicostops.intelligence.domain.CostHistoryCoverage.Scope;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cost-intelligence/history-coverage")
public class CostHistoryCoverageController {
    private final CostHistoryCoverageQueryService queries;
    private final CostHistoryCoverageCommandService commands;

    public CostHistoryCoverageController(CostHistoryCoverageQueryService queries,
            CostHistoryCoverageCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    public CostHistoryCoverageQueryService.CoverageView coverage(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam String currency,
            @RequestParam Scope scope, @RequestParam long scopeId,
            @RequestParam LocalDate start, @RequestParam LocalDate end) {
        return queries.coverage(user, currency, scope, scopeId, start, end);
    }

    @GetMapping("/scopes")
    public java.util.List<com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper.ScopeOption> scopes(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam(defaultValue = "") String search) {
        return queries.scopes(user, search);
    }

    @PutMapping
    public CostHistoryCoverageCommandService.ReviewResult review(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CostHistoryCoverageCommandService.ReviewRequest request) {
        return commands.review(user, request);
    }

    @PostMapping("/analyze")
    public com.aicostops.intelligence.application.AnalysisRunOutcome analyze(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam String currency) {
        return commands.refreshAnalysis(user, currency);
    }
}
