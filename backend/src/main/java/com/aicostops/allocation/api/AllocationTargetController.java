package com.aicostops.allocation.api;

import com.aicostops.allocation.application.AllocationTargetQueryService;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Allocation target directory API: the ACTIVE same-org project, cost center,
 * and team safe refs for editors. ALLOCATION_EDIT at ORG scope is enforced by
 * the query service before any directory lookup.
 */
@RestController
@RequestMapping("/api/v1/allocation-targets")
public class AllocationTargetController {

    private final AllocationTargetQueryService queries;

    public AllocationTargetController(AllocationTargetQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<AllocationTargetResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return queries.list(authenticatedUser).stream()
                .map(AllocationTargetResponse::from)
                .toList();
    }

    /** HTTP shape of one allocatable target; ids are strings. */
    public record AllocationTargetResponse(String type, ApiId id, String name) {

        static AllocationTargetResponse from(
                com.aicostops.attribution.application.AllocationTargetDirectory.TargetRef ref) {
            return new AllocationTargetResponse(ref.type(), ApiId.of(ref.id()), ref.name());
        }
    }
}
