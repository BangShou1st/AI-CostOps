package com.aicostops.advisor.api;

import com.aicostops.advisor.application.AdvisorProfileService;
import com.aicostops.advisor.application.AdvisorJobCommandService;
import com.aicostops.advisor.application.AdvisorJobQueryService;
import com.aicostops.advisor.application.AdvisorRequests.ExplanationRequest;
import com.aicostops.advisor.application.AdvisorReadModels.JobResponse;
import com.aicostops.advisor.application.AdvisorReadModels.ProfileResponse;
import com.aicostops.advisor.application.AdvisorRequests.UpdateProfileRequest;
import com.aicostops.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-advisor")
public class AdvisorController {

    private final AdvisorProfileService profiles;
    private final AdvisorJobCommandService commands;
    private final AdvisorJobQueryService queries;

    public AdvisorController(AdvisorProfileService profiles, AdvisorJobCommandService commands,
            AdvisorJobQueryService queries) {
        this.profiles = profiles;
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/profile")
    public ProfileResponse profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return profiles.profile(user);
    }

    @PutMapping("/profile")
    public ProfileResponse updateProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateProfileBody body) {
        return profiles.updateProfile(user, new UpdateProfileRequest(body.providerModelId(),
                body.projectId(), body.financialScopeType(), body.financialScopeId(),
                body.budgetEnforcementMode()));
    }

    /**
     * P1: server-generated evidence. The client supplies only the subject identity; any
     * money/drivers/summaries in the payload are ignored (never trusted) so clients cannot
     * forge dollar amounts or fact references.
     */
    @PostMapping("/explanations")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse requestExplanation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ExplanationBody body) {
        return commands.requestExplanation(user, new ExplanationRequest(body.subjectType(), body.subjectId()));
    }

    @GetMapping("/explanations/{id}")
    public JobResponse explanation(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return queries.explanation(user, id);
    }

    @PostMapping("/explanations/{id}/retry")
    public JobResponse retry(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return commands.retry(user, id);
    }

    public record UpdateProfileBody(
            long providerModelId,
            long projectId,
            String financialScopeType,
            long financialScopeId,
            String budgetEnforcementMode) {
    }

    public record ExplanationBody(String subjectType, long subjectId) {
    }
}
