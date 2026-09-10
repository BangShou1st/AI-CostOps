package com.aicostops.gatewayadmin.api;

import com.aicostops.gatewayadmin.application.GatewayCredentialService;
import com.aicostops.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Governed Gateway Credential surface. {@code POST /} returns the raw key
 * exactly once; every other endpoint exposes metadata only (the digest is
 * never selected, the raw key is never recoverable).
 */
@RestController
@RequestMapping("/api/v1/gateway-credentials")
public class GatewayCredentialController {

    private final GatewayCredentialService service;

    public GatewayCredentialController(GatewayCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public List<ControlPlaneDtos.CredentialResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user);
    }

    @GetMapping("/{id}")
    public ControlPlaneDtos.CredentialResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ControlPlaneDtos.CredentialCreateResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ControlPlaneDtos.CredentialCreateRequest request) {
        return service.create(user, request);
    }

    @PostMapping("/{id}/revoke")
    public ControlPlaneDtos.CredentialResponse revoke(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.revoke(user, id);
    }
}