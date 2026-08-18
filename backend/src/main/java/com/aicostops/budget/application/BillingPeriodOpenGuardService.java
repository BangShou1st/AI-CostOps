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
 *
 * <p>Overlapping periods are not excluded by the schema, so the guard fails
 * closed: more than one covering candidate is an ambiguous period identity
 * and is rejected with {@code STATE_CONFLICT} before any status decision.
 */
@Service
public class BillingPeriodOpenGuardService implements BillingPeriodOpenGuard {

    private final BillingPeriodMapper mapper;

    public BillingPeriodOpenGuardService(BillingPeriodMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public BillingPeriod requireOpen(long organizationId, Instant transactionTime) {
        var candidates = mapper.selectCoveringCandidates(organizationId, transactionTime);
        if (candidates.isEmpty()) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "No covering billing period",
                    "No billing period of the organization covers the transaction time "
                            + transactionTime
                            + "; open a covering period before writing financial data.");
        }
        if (candidates.size() > 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Ambiguous covering billing periods",
                    "The current organization has ambiguous (overlapping) billing periods "
                            + "covering " + transactionTime
                            + "; resolve the period overlap before writing financial data.");
        }
        var period = candidates.get(0);
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