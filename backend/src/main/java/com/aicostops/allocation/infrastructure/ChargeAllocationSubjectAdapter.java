package com.aicostops.allocation.infrastructure;

import com.aicostops.allocation.application.AllocationSubjectPort;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * CHARGE_FACT subject adapter: locks the charge, enforces the confirmed-import
 * lineage + CLEAN review-status gates, and writes the charge current-decision
 * pointer. Semantics mirror the pre-refactor command service 1:1.
 */
@Component
public class ChargeAllocationSubjectAdapter implements AllocationSubjectPort {

    private final AllocationChargeFactMapper charges;

    public ChargeAllocationSubjectAdapter(AllocationChargeFactMapper charges) {
        this.charges = charges;
    }

    @Override
    public AllocationSubjectType subjectType() {
        return AllocationSubjectType.CHARGE_FACT;
    }

    @Override
    public SubjectLoad loadForUpdate(long organizationId, long subjectId) {
        var charge = Optional.ofNullable(charges.selectChargeForUpdate(organizationId, subjectId))
                .orElseThrow(this::chargeNotFound);
        return new SubjectLoad(
                charge.id(),
                charge.amount(),
                charge.currency(),
                charge.currentAllocationDecisionId(),
                charge.reviewStatus().name());
    }

    @Override
    public void assertConfirmEligible(long organizationId, SubjectLoad load) {
        var charge = Optional.ofNullable(charges.selectChargeForUpdate(organizationId, load.subjectId()))
                .orElseThrow(this::chargeNotFound);
        var lineage = charges.selectLineage(organizationId, charge.id());
        if (lineage == null || !lineage.confirmedImport()) {
            throw notEligible(
                    "The charge does not belong to the confirmed import lineage.");
        }
        if (charge.reviewStatus() != ReviewStatus.CLEAN) {
            throw notEligible(
                    "Only CLEAN charges are eligible for allocation confirm.");
        }
        if (load.currentAllocationDecisionId() != null) {
            throw alreadyConfirmed();
        }
    }

    @Override
    public void setCurrentDecisionPointer(long organizationId, long subjectId, long decisionId) {
        if (charges.updateCurrentDecisionPointer(organizationId, subjectId, decisionId) != 1) {
            throw new IllegalStateException(
                    "The charge current-decision pointer update must affect exactly one row");
        }
    }

    private DomainException chargeNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Charge not found",
                "The charge is not available in the current organization.");
    }

    private static DomainException alreadyConfirmed() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_ALREADY_CONFIRMED,
                "Allocation already confirmed",
                "The charge already has a confirmed allocation that cannot be rewritten.");
    }

    private static DomainException notEligible(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.ALLOCATION_NOT_ELIGIBLE,
                "Charge not eligible for allocation",
                detail);
    }
}