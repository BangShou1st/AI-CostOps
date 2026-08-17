package com.aicostops.cost.api;

import com.aicostops.cost.application.CostReadModels.ChargeCostDetailRow;
import com.aicostops.cost.application.CostReadModels.ChargeCostRow;
import com.aicostops.cost.application.CostQueryService;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChargeFact cost read API. Authorization runs in the HTTP layer before any
 * resource lookup (403 for a missing grant, privacy-preserving 404 otherwise).
 */
@RestController
@RequestMapping("/api/v1/costs/charges")
public class CostController {

    private static final String PERMISSION_COST_READ = "COST_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final CostQueryService queries;

    public CostController(
            AuthorizationContextService authorizationContexts,
            CostQueryService queries) {
        this.authorizationContexts = authorizationContexts;
        this.queries = queries;
    }

    @GetMapping
    public PageResponse<ChargeCostResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String reviewStatus) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, PERMISSION_COST_READ);
        var result = queries.listCharges(context.organizationId(), page, size, reviewStatus);
        return new PageResponse<>(
                result.items().stream().map(ChargeCostResponse::from).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping("/{chargeFactId}")
    public ChargeCostDetailResponse get(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable long chargeFactId) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, PERMISSION_COST_READ);
        var row = queries.getCharge(context.organizationId(), chargeFactId);
        if (row == null) {
            throw notFound();
        }
        return ChargeCostDetailResponse.from(row);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Charge not found",
                "The charge is not available in the current organization.");
    }

    public record ChargeCostResponse(
            ApiId id,
            String providerCode,
            String chargeCategory,
            String amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus,
            ApiId currentAllocationDecisionId) {

        public static ChargeCostResponse from(ChargeCostRow row) {
            return new ChargeCostResponse(
                    ApiId.of(row.id()),
                    row.providerCode(),
                    row.chargeCategory().name(),
                    row.amount().toPlainString(),
                    row.currency(),
                    row.periodStart(),
                    row.periodEnd(),
                    row.reviewStatus(),
                    row.currentAllocationDecisionId() == null
                            ? null
                            : ApiId.of(row.currentAllocationDecisionId()));
        }
    }

    public record ChargeCostDetailResponse(
            ApiId id,
            String providerCode,
            String chargeCategory,
            String amount,
            String currency,
            Instant periodStart,
            Instant periodEnd,
            ReviewStatus reviewStatus,
            ApiId currentAllocationDecisionId,
            ApiId duplicateOfChargeId,
            boolean confirmedImport) {

        public static ChargeCostDetailResponse from(ChargeCostDetailRow row) {
            return new ChargeCostDetailResponse(
                    ApiId.of(row.id()),
                    row.providerCode(),
                    row.chargeCategory().name(),
                    row.amount().toPlainString(),
                    row.currency(),
                    row.periodStart(),
                    row.periodEnd(),
                    row.reviewStatus(),
                    row.currentAllocationDecisionId() == null
                            ? null
                            : ApiId.of(row.currentAllocationDecisionId()),
                    row.duplicateOfChargeId() == null
                            ? null
                            : ApiId.of(row.duplicateOfChargeId()),
                    row.confirmedImport());
        }
    }
}
