package com.aicostops.cost.infrastructure;

import com.aicostops.cost.application.ChargePostingPort;
import com.aicostops.cost.application.ChargePostingPort.ChargePostingSource;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Owns the provider-charge gates needed by the Ledger posting workflow. */
@Component
public class ChargePostingAdapter implements ChargePostingPort {

    private final ChargePostingMapper mapper;

    public ChargePostingAdapter(ChargePostingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ChargePostingSource load(long organizationId, long chargeFactId) {
        var source = mapper.selectForPosting(organizationId, chargeFactId);
        if (source == null) {
            throw notFound();
        }
        return source;
    }

    @Override
    public ChargePostingSource lockAndRequirePostable(
            long organizationId, long chargeFactId, long expectedDecisionId) {
        var source = mapper.selectForPostingForUpdate(organizationId, chargeFactId);
        if (source == null) {
            throw notFound();
        }
        if (!source.confirmedImport()) {
            throw notEligible("The charge does not belong to a confirmed import attempt.");
        }
        if (source.reviewStatus() != ReviewStatus.CLEAN) {
            throw notEligible("Only CLEAN charges can be posted.");
        }
        if (source.periodStart() == null) {
            throw notEligible("The charge has no period_start and cannot be posted.");
        }
        if (source.currentAllocationDecisionId() == null
                || source.currentAllocationDecisionId() != expectedDecisionId) {
            throw notEligible("The charge allocation pointer does not match the posting decision.");
        }
        return source;
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Charge not found", "The charge is not available in the current organization.");
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Charge is not postable", detail);
    }
}
