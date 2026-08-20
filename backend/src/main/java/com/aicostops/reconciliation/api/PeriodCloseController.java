package com.aicostops.reconciliation.api;

import com.aicostops.reconciliation.application.PeriodCloseQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PeriodCloseController {

    private final PeriodCloseQueryService queries;

    public PeriodCloseController(PeriodCloseQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/billing-periods/{periodId}/close-readiness")
    public PeriodCloseResponses.ReadinessResponse readiness(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long periodId) {
        return PeriodCloseResponses.ReadinessResponse.from(queries.preview(user, periodId));
    }
}
