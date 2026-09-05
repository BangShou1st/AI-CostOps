package com.aicostops.routing.api;

import com.aicostops.routing.application.RoutingPolicyService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routing-policies")
public class RoutingPolicyController {

    private final RoutingPolicyService service;

    public RoutingPolicyController(RoutingPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<RoutingPolicyDtos.PolicyResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(user, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public RoutingPolicyDtos.PolicyResponse get(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        return service.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingPolicyDtos.PolicyResponse create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RoutingPolicyDtos.CreateRequest request) {
        return service.create(user, request);
    }

    @PostMapping("/{id}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutingPolicyDtos.PolicyResponse revise(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        return service.revise(user, id);
    }

    @PutMapping("/{id}")
    public RoutingPolicyDtos.PolicyResponse update(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id, @Valid @RequestBody RoutingPolicyDtos.UpdateRequest request) {
        return service.update(user, id, request);
    }

    @PostMapping("/{id}/activate")
    public RoutingPolicyDtos.PolicyResponse activate(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        return service.activate(user, id);
    }
}
