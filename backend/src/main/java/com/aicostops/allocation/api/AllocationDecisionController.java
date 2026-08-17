package com.aicostops.allocation.api;

import static com.aicostops.allocation.api.AllocationDecisionRequests.parse;
import static com.aicostops.allocation.api.AllocationDecisionRequests.parseLines;

import com.aicostops.allocation.api.AllocationDecisionRequests.ManualDraftRequest;
import com.aicostops.allocation.api.AllocationDecisionRequests.ReplaceLinesRequest;
import com.aicostops.allocation.api.AllocationDecisionResponses.AllocationDecisionResponse;
import com.aicostops.allocation.api.AllocationDecisionResponses.AllocationProposalResponse;
import com.aicostops.allocation.application.AllocationDecisionCommandService;
import com.aicostops.allocation.application.AllocationDecisionQueryService;
import com.aicostops.allocation.application.AllocationProposalService;
import com.aicostops.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Allocation decision API: reads, manual draft create, replace-lines edit,
 * confirm, and (Gate B) the rule proposal. Authorization is enforced by the
 * command/query services before any resource lookup.
 */
@RestController
@RequestMapping("/api/v1")
public class AllocationDecisionController {

    private final AllocationDecisionQueryService queries;
    private final AllocationDecisionCommandService commands;
    private final AllocationProposalService proposals;

    public AllocationDecisionController(
            AllocationDecisionQueryService queries,
            AllocationDecisionCommandService commands,
            AllocationProposalService proposals) {
        this.queries = queries;
        this.commands = commands;
        this.proposals = proposals;
    }

    @GetMapping("/costs/charges/{chargeFactId}/allocation-decisions")
    public List<AllocationDecisionResponse> listByCharge(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long chargeFactId) {
        return queries.listByCharge(authenticatedUser, chargeFactId).stream()
                .map(AllocationDecisionResponse::from)
                .toList();
    }

    @GetMapping("/allocation-decisions/{decisionId}")
    public AllocationDecisionResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long decisionId) {
        return AllocationDecisionResponse.from(queries.get(authenticatedUser, decisionId));
    }

    @PostMapping("/costs/charges/{chargeFactId}/allocation-decisions/manual")
    public AllocationDecisionResponse createManualDraft(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long chargeFactId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ManualDraftRequest request) {
        return AllocationDecisionResponse.from(commands.createManualDraft(
                authenticatedUser, chargeFactId, parse(request), idempotencyKey));
    }

    @PutMapping("/allocation-decisions/{decisionId}/lines")
    public AllocationDecisionResponse replaceLines(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long decisionId,
            @Valid @RequestBody ReplaceLinesRequest request) {
        return AllocationDecisionResponse.from(commands.replaceLines(
                authenticatedUser, decisionId, parseLines(request.lines())));
    }

    @PostMapping("/allocation-decisions/{decisionId}/confirm")
    public AllocationDecisionResponse confirm(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long decisionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AllocationDecisionResponse.from(commands.confirm(
                authenticatedUser, decisionId, idempotencyKey));
    }

    @PostMapping("/costs/charges/{chargeFactId}/allocation-proposal")
    public AllocationProposalResponse propose(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long chargeFactId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return AllocationProposalResponse.from(proposals.propose(
                authenticatedUser, chargeFactId, idempotencyKey));
    }
}
