package com.aicostops.reporting.api;

import com.aicostops.reporting.api.WorkbenchResponses.WorkbenchResponse;
import com.aicostops.reporting.application.WorkbenchQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregated read-only workbench for the authenticated member's organization.
 * Authentication is required; each response section is populated only when
 * the caller holds that section's permission with ORG scope, so a login
 * without finance grants still renders an (empty) landing dashboard.
 */
@RestController
@RequestMapping("/api/v1/workbench")
public class WorkbenchController {

    private final WorkbenchQueryService workbench;

    public WorkbenchController(WorkbenchQueryService workbench) {
        this.workbench = workbench;
    }

    @GetMapping
    public WorkbenchResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) Long billingPeriodId) {
        return WorkbenchResponse.from(workbench.get(authenticatedUser, billingPeriodId));
    }
}
