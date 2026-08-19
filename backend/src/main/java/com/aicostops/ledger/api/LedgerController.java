package com.aicostops.ledger.api;

import com.aicostops.ledger.api.LedgerRequests.PostSourceRequest;
import com.aicostops.ledger.api.LedgerResponses.LedgerPostingDetailResponse;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.ledger.application.ExpensePostingService;
import com.aicostops.ledger.application.LedgerQueryService;
import com.aicostops.ledger.domain.LedgerSourceType;
import com.aicostops.shared.web.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.aicostops.shared.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M5 source-posting commands. Ledger query endpoints arrive in Task 7. */
@RestController
@RequestMapping("/api/v1")
public class LedgerController {

    private final ProviderChargePostingService providerCharges;
    private final ExpensePostingService expenses;
    private final LedgerQueryService queries;

    public LedgerController(ProviderChargePostingService providerCharges,
            ExpensePostingService expenses, LedgerQueryService queries) {
        this.providerCharges = providerCharges;
        this.expenses = expenses;
        this.queries = queries;
    }

    @PostMapping("/costs/charges/{chargeFactId}/post")
    public LedgerPostingDetailResponse postCharge(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long chargeFactId,
            @RequestBody(required = false) PostSourceRequest request) {
        return LedgerPostingDetailResponse.from(providerCharges.post(authenticatedUser, chargeFactId,
                LedgerRequests.parsePost(request)));
    }

    @PostMapping("/expenses/{expenseId}/post")
    public LedgerPostingDetailResponse postExpense(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long expenseId,
            @RequestBody(required = false) PostSourceRequest request) {
        return LedgerPostingDetailResponse.from(expenses.post(authenticatedUser, expenseId,
                LedgerRequests.parsePost(request)));
    }

    @GetMapping("/ledger/postings")
    public com.aicostops.shared.web.PageResponse<LedgerResponses.LedgerPostingSummaryResponse> listPostings(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) Long billingPeriodId,
            @RequestParam(required = false) LedgerSourceType sourceType,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long costCenterId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "postedAt,desc") String sort) {
        return LedgerResponses.postingPage(queries.listPostings(authenticatedUser,
                PageRequest.of(page, size), billingPeriodId, sourceType, projectId, costCenterId,
                teamId, sort));
    }

    @GetMapping("/ledger/postings/{postingId}")
    public LedgerResponses.LedgerPostingSummaryResponse getPosting(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long postingId) {
        return LedgerResponses.LedgerPostingSummaryResponse.from(
                queries.getPosting(authenticatedUser, postingId));
    }

    @GetMapping("/ledger/entries")
    public com.aicostops.shared.web.PageResponse<LedgerResponses.LedgerEntryResponse> listEntries(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) Long billingPeriodId,
            @RequestParam(required = false) LedgerSourceType sourceType,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long costCenterId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "postedAt,desc") String sort) {
        return LedgerResponses.entryPage(queries.listEntries(authenticatedUser,
                PageRequest.of(page, size), billingPeriodId, sourceType, projectId, costCenterId,
                teamId, sort));
    }

    @GetMapping("/ledger/entries/{entryId}")
    public LedgerResponses.LedgerEntryDetailResponse getEntry(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long entryId) {
        return LedgerResponses.LedgerEntryDetailResponse.from(queries.getEntry(authenticatedUser, entryId));
    }
}
