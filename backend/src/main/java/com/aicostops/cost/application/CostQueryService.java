package com.aicostops.cost.application;

import com.aicostops.cost.application.CostReadModels.ChargeCostDetailRow;
import com.aicostops.cost.application.CostReadModels.ChargeCostRow;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ChargeFact cost reads. The caller (HTTP layer) resolves the authorization
 * context and requires COST_READ at ORG scope before invoking these methods.
 */
@Service
public class CostQueryService {

    private final ChargeFactRepository charges;

    public CostQueryService(ChargeFactRepository charges) {
        this.charges = charges;
    }

    public PageResponse<ChargeCostRow> listCharges(
            long organizationId, int page, int size, String reviewStatus) {
        var pageRequest = validPage(page, size);
        var status = parseReviewStatus(reviewStatus);
        var items = charges.pageCharges(organizationId, statusName(status),
                pageRequest.size(), pageRequest.page() * pageRequest.size());
        var total = charges.countCharges(organizationId, statusName(status));
        return PageResponse.of(items, pageRequest, total);
    }

    public ChargeCostDetailRow getCharge(long organizationId, long chargeFactId) {
        return charges.selectChargeDetail(organizationId, chargeFactId);
    }

    private static String statusName(ReviewStatus status) {
        return status == null ? null : status.name();
    }

    private static ReviewStatus parseReviewStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ReviewStatus.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid filter value",
                    "The reviewStatus filter must be a valid enum value: " + value);
        }
    }

    private static PageRequest validPage(int page, int size) {
        try {
            return PageRequest.of(page, size);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid page or size",
                    "Page must be zero or greater and size must be between 1 and 200.");
        }
    }
}
