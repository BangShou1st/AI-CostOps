package com.aicostops.budget.api;

import com.aicostops.budget.api.BillingPeriodResponses.BillingPeriodResponse;
import com.aicostops.budget.application.BillingPeriodQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/billing-periods")
public class BillingPeriodController {

    private final BillingPeriodQueryService queries;

    public BillingPeriodController(BillingPeriodQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<BillingPeriodResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return queries.list(authenticatedUser).stream()
                .map(BillingPeriodResponse::from)
                .toList();
    }
}
