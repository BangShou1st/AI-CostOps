package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BillingPeriodFinancialWriteFenceService implements BillingPeriodFinancialWriteFence {

    private final BillingPeriodMapper mapper;

    public BillingPeriodFinancialWriteFenceService(BillingPeriodMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public BillingPeriod lockOpenAt(long organizationId, Instant effectiveAt) {
        var candidates = mapper.selectCoveringCandidatesForUpdate(organizationId, effectiveAt);
        if (candidates.isEmpty()) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "No covering billing period",
                    "No billing period of the organization covers the transaction time "
                            + effectiveAt + "; open a covering period before writing financial data.");
        }
        if (candidates.size() > 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Ambiguous covering billing periods",
                    "The current organization has ambiguous billing periods covering "
                            + effectiveAt + "; resolve the overlap before writing financial data.");
        }
        return requireOpen(candidates.getFirst());
    }

    @Override
    public BillingPeriod lockOpenById(long organizationId, long billingPeriodId) {
        var period = mapper.selectByIdForUpdate(organizationId, billingPeriodId);
        if (period == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Billing period not found",
                    "The billing period is not available in the current organization.");
        }
        return requireOpen(period);
    }

    @Override
    public void lockOrganizationAndRequireNoClosingPeriod(long organizationId) {
        if (mapper.lockOrganization(organizationId) == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Organization not found",
                    "The current organization is not available.");
        }
        if (mapper.countClosingByOrganization(organizationId) > 0) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                    "A billing period is closing",
                    "New organization-level financial truth is not admitted while a billing period is CLOSING.");
        }
    }

    @Override
    public boolean hasClosingPeriod(long organizationId) {
        return mapper.countClosingByOrganization(organizationId) > 0;
    }

    private static BillingPeriod requireOpen(BillingPeriod period) {
        if (period.status() != BillingPeriodStatus.OPEN) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                    "Billing period is not open",
                    "Ordinary financial writes require an OPEN billing period; current status is "
                            + period.status() + ".");
        }
        return period;
    }
}
