package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class BillingPeriodCloseService implements BillingPeriodClosePort {

    private final BillingPeriodMapper mapper;

    public BillingPeriodCloseService(BillingPeriodMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockOrganizationAdmission(long organizationId) {
        if (mapper.lockOrganization(organizationId) == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Organization not found", "The organization is not available.");
        }
    }

    @Override
    public BillingPeriod lockPeriod(long organizationId, long periodId) {
        var period = mapper.selectByIdForUpdate(organizationId, periodId);
        if (period == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Billing period not found",
                    "The billing period is not available in the current organization.");
        }
        return period;
    }

    @Override
    public BillingPeriod markClosing(long organizationId, long periodId,
            long expectedVersion, Instant now) {
        if (mapper.markClosing(organizationId, periodId, expectedVersion, now) != 1) {
            throw conflict("Billing period could not enter CLOSING.");
        }
        return requireLocked(organizationId, periodId);
    }

    @Override
    public BillingPeriod returnOpen(long organizationId, long periodId,
            long expectedVersion, Instant now) {
        if (mapper.returnOpen(organizationId, periodId, expectedVersion, now) != 1) {
            throw conflict("Billing period could not return from CLOSING to OPEN.");
        }
        return requireLocked(organizationId, periodId);
    }

    @Override
    public BillingPeriod markClosed(long organizationId, long periodId,
            long expectedVersion, Instant now) {
        if (mapper.markClosed(organizationId, periodId, expectedVersion, now) != 1) {
            throw conflict("Billing period could not enter CLOSED.");
        }
        return requireLocked(organizationId, periodId);
    }

    @Override
    public BillingPeriod reopen(long organizationId, long periodId,
            long expectedVersion, Instant now) {
        if (mapper.reopen(organizationId, periodId, expectedVersion, now) != 1) {
            throw conflict("Billing period could not reopen.");
        }
        return requireLocked(organizationId, periodId);
    }

    private BillingPeriod requireLocked(long organizationId, long periodId) {
        var period = mapper.selectByIdForUpdate(organizationId, periodId);
        if (period == null) {
            throw new IllegalStateException("A just-updated billing period must be readable");
        }
        return period;
    }

    private static DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Billing period state conflict", detail);
    }
}
