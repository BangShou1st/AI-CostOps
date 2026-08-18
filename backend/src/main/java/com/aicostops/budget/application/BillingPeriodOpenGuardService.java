package com.aicostops.budget.application;

import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.infrastructure.BillingPeriodMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link BillingPeriodOpenGuard}.
 *
 * <p>Lookups are organization scoped and the half-open range is enforced in
 * SQL ({@code period_start <= at AND period_end > at}), so the start boundary
 * is included, the end boundary is excluded, and a wrong-organization query
 * behaves exactly like a query with no covering period — no existence leak
 * across organizations.
 */
@Service
public class BillingPeriodOpenGuardService implements BillingPeriodOpenGuard {

    private final BillingPeriodMapper mapper;

    public BillingPeriodOpenGuardService(BillingPeriodMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public BillingPeriod requireOpen(long organizationId, Instant transactionTime) {
        var period = mapper.selectCovering(organizationId, transactionTime);
        if (period == null) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "No covering billing period",
                    "No billing period of the organization covers the transaction time "
                            + transactionTime
                            + "; open a covering period before writing financial data.");
        }
        if (period.status() != BillingPeriodStatus.OPEN) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.PERIOD_NOT_OPEN,
                    "Billing period is not open",
                    "The billing period covering " + transactionTime + " is "
                            + period.status()
                            + "; ordinary financial writes require an OPEN period.");
        }
        return period;
    }
}