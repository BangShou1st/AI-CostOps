package com.aicostops.organization.api;

import com.aicostops.organization.application.ProviderAccountService;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/provider-accounts")
public class ProviderAccountController {

    private final ProviderAccountService providerAccounts;

    public ProviderAccountController(ProviderAccountService providerAccounts) {
        this.providerAccounts = providerAccounts;
    }

    @GetMapping
    public PageResponse<ProviderAccountResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(required = false) MasterDataStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return providerAccounts.list(authenticatedUser, status, PageRequest.of(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderAccountResponse create(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateProviderAccountRequest request) {
        return providerAccounts.create(authenticatedUser, request);
    }

    @PatchMapping("/{id}")
    public ProviderAccountResponse update(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long id,
            @Valid @RequestBody UpdateProviderAccountRequest request) {
        return providerAccounts.update(authenticatedUser, id, request);
    }
}
