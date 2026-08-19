package com.aicostops.ledger.api;

import com.aicostops.ledger.api.LedgerRequests.PostSourceRequest;
import com.aicostops.ledger.api.LedgerResponses.LedgerPostingDetailResponse;
import com.aicostops.ledger.application.ProviderChargePostingService;
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

    public LedgerController(ProviderChargePostingService providerCharges) {
        this.providerCharges = providerCharges;
    }

    @PostMapping("/costs/charges/{chargeFactId}/post")
    public LedgerPostingDetailResponse postCharge(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long chargeFactId,
            @RequestBody(required = false) PostSourceRequest request) {
        return LedgerPostingDetailResponse.from(providerCharges.post(authenticatedUser, chargeFactId,
                LedgerRequests.parsePost(request)));
    }
}
