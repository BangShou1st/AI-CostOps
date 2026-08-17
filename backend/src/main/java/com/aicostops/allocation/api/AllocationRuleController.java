package com.aicostops.allocation.api;

import static com.aicostops.allocation.api.AllocationRuleRequests.parse;

import com.aicostops.allocation.api.AllocationRuleRequests.RuleVersionRequest;
import com.aicostops.allocation.application.AllocationRuleCommandService;
import com.aicostops.allocation.application.AllocationRuleQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Allocation rule API: list/detail reads, append-only version creation, and
 * lifecycle archiving. ALLOCATION_RULE_MANAGE at ORG scope is enforced by the
 * services before any resource lookup.
 */
@RestController
@RequestMapping("/api/v1/allocation-rules")
public class AllocationRuleController {

    private final AllocationRuleQueryService queries;
    private final AllocationRuleCommandService commands;

    public AllocationRuleController(
            AllocationRuleQueryService queries,
            AllocationRuleCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    public PageResponse<AllocationRuleResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = queries.list(authenticatedUser, page, size);
        return new PageResponse<>(
                result.items().stream().map(AllocationRuleResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/{ruleId}")
    public AllocationRuleResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long ruleId) {
        return AllocationRuleResponse.from(queries.get(authenticatedUser, ruleId));
    }

    @PostMapping("/{ruleKey}/versions")
    public AllocationRuleResponse createVersion(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable String ruleKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RuleVersionRequest request) {
        return AllocationRuleResponse.from(commands.createVersion(
                authenticatedUser, ruleKey, parse(request), idempotencyKey));
    }

    @PostMapping("/{ruleId}/archive")
    public AllocationRuleResponse archive(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long ruleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AllocationRuleResponse.from(commands.archive(
                authenticatedUser, ruleId, idempotencyKey));
    }
}
