package com.aicostops.gatewayadmin.api;

import com.aicostops.gatewayadmin.application.ServiceIdentityService;
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

/** Governed Service Identity surface (list, create, status view). */
@RestController
@RequestMapping("/api/v1/service-identities")
public class ServiceIdentityController {

    private final ServiceIdentityService service;

    public ServiceIdentityController(ServiceIdentityService service) {
        this.service = service;
    }

    @GetMapping
    public List<ControlPlaneDtos.ServiceIdentityResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user);
    }

    @GetMapping("/{id}")
    public ControlPlaneDtos.ServiceIdentityResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.get(user, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ControlPlaneDtos.ServiceIdentityResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ControlPlaneDtos.ServiceIdentityRequest request) {
        return service.create(user, request);
    }
}