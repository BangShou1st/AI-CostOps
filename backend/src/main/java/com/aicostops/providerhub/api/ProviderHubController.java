package com.aicostops.providerhub.api;

import com.aicostops.providerhub.api.ProviderHubDtos.ConnectionResponse;
import com.aicostops.providerhub.api.ProviderHubDtos.CreateConnectionRequest;
import com.aicostops.providerhub.api.ProviderHubDtos.ProbeResponse;
import com.aicostops.providerhub.api.ProviderHubDtos.TemplateResponse;
import com.aicostops.providerhub.api.ProviderHubDtos.UpdateConnectionRequest;
import com.aicostops.providerhub.application.ModelDiscoveryService;
import com.aicostops.providerhub.application.ModelDiscoveryService.DiscoveryResponse;
import com.aicostops.providerhub.application.ModelDiscoveryService.PromotionResponse;
import com.aicostops.providerhub.application.ModelProbeService;
import com.aicostops.providerhub.application.ModelProbeService.ProbeResult;
import com.aicostops.providerhub.application.ProviderConnectionService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1")
public class ProviderHubController {

    private final ProviderConnectionService connections;
    private final ModelDiscoveryService discovery;
    private final ModelProbeService probe;

    public ProviderHubController(
            ProviderConnectionService connections,
            ModelDiscoveryService discovery,
            ModelProbeService probe) {
        this.connections = connections;
        this.discovery = discovery;
        this.probe = probe;
    }

    @GetMapping("/provider-templates")
    public List<TemplateResponse> templates(@AuthenticationPrincipal AuthenticatedUser user) {
        return connections.templates(user);
    }

    @GetMapping("/provider-connections")
    public PageResponse<ConnectionResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return connections.list(user, PageRequest.of(page, size));
    }

    @PostMapping("/provider-connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateConnectionRequest request) {
        return connections.create(user, request);
    }

    @GetMapping("/provider-connections/{id}")
    public ConnectionResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return connections.get(user, id);
    }

    @PutMapping("/provider-connections/{id}")
    public ConnectionResponse updateDraft(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @Valid @RequestBody UpdateConnectionRequest request) {
        return connections.updateDraft(user, id, request);
    }

    @GetMapping("/provider-connections/{id}/revisions")
    public List<ConnectionResponse> revisions(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return connections.revisionsOf(user, id);
    }

    @PostMapping("/provider-connections/{id}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse createRevision(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return connections.createRevision(user, id);
    }

    @PostMapping("/provider-connections/{id}/activate")
    public ConnectionResponse activate(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return connections.activate(user, id);
    }

    @PostMapping("/provider-connections/{id}/probe")
    public ProbeResponse probeConnection(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return connections.probe(user, id);
    }

    @GetMapping("/provider-connections/{id}/models")
    public List<DiscoveryResponse> models(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return discovery.list(user, id);
    }

    @PostMapping("/provider-connections/{id}/models/refresh")
    public List<DiscoveryResponse> refreshModels(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestBody(required = false) RefreshModelsRequest request) {
        var observed = request == null || request.modelNames() == null ? List.<String>of() : request.modelNames();
        return discovery.refresh(user, id, observed);
    }

    @PostMapping("/provider-connections/{id}/models/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscoveryResponse manualModel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @Valid @RequestBody ManualModelRequest request) {
        return discovery.registerManual(user, id, request.modelName());
    }

    @PostMapping("/provider-connections/{id}/models/{discoveryId}/probe")
    public ProbeResult probeModel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @PathVariable long discoveryId) {
        return probe.probe(user, id, discoveryId);
    }

    @PostMapping("/provider-connections/{id}/models/{discoveryId}/promote")
    @ResponseStatus(HttpStatus.CREATED)
    public PromotionResponse promoteModel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @PathVariable long discoveryId) {
        return discovery.promote(user, id, discoveryId);
    }

    public record RefreshModelsRequest(List<String> modelNames) {
    }

    public record ManualModelRequest(String modelName) {
    }
}
