package com.aicostops.gatewayadmin.api;

import com.aicostops.gatewayadmin.application.ModelPricingService;
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

/** Governed Model/Pricing setup surface for the Browser UAT lifecycle. */
@RestController
public class ModelPricingController {

    private final ModelPricingService service;

    public ModelPricingController(ModelPricingService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/model-catalog")
    public List<ControlPlaneDtos.ModelCatalogResponse> listModels(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.listModels(user);
    }

    @GetMapping("/api/v1/provider-models")
    public List<ControlPlaneDtos.ProviderModelResponse> listProviderModels(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.listProviderModels(user);
    }

    @GetMapping("/api/v1/pricing-versions")
    public List<ControlPlaneDtos.PricingVersionResponse> listPricingVersions(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return service.listPricingVersions(user);
    }

    @PostMapping("/api/v1/pricing-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ControlPlaneDtos.PricingVersionResponse createPricingVersion(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ControlPlaneDtos.PricingVersionCreateRequest request) {
        return service.createPricingVersion(user, request);
    }

    @PostMapping("/api/v1/pricing-versions/{id}/activate")
    public ControlPlaneDtos.PricingVersionResponse activate(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return service.activate(user, id);
    }
}