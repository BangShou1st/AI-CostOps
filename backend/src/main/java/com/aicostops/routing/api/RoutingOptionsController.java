package com.aicostops.routing.api;

import com.aicostops.routing.application.RoutingPolicyService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routing-options")
public class RoutingOptionsController {

    private final RoutingPolicyService service;

    public RoutingOptionsController(RoutingPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<RoutingPolicyDtos.RouteOptionResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam long modelId) {
        return service.options(user, modelId);
    }
}
