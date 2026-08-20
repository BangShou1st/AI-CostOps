package com.aicostops.reconciliation.application;

import com.aicostops.budget.application.BillingPeriodReadPort;
import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class PeriodCloseQueryService {

    private static final String PERMISSION_READ = "PERIOD_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodReadPort periods;
    private final CloseBlockerRegistry blockers;

    public PeriodCloseQueryService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodReadPort periods,
            CloseBlockerRegistry blockers) {
        this.authorizationContexts = authorizationContexts;
        this.periods = periods;
        this.blockers = blockers;
    }

    public CloseReadiness preview(AuthenticatedUser user, long periodId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        var period = requirePeriod(context.organizationId(), periodId);
        var blockerContext = new CloseBlockerContext(
                context.organizationId(), period.id(), period.periodStart(), period.periodEnd());
        var results = new ArrayList<CloseBlockerResult>(blockers.providers().size());
        for (var provider : blockers.providers()) {
            try {
                results.add(provider.evaluate(blockerContext));
            } catch (RuntimeException failure) {
                results.add(CloseBlockerResult.error(provider.code(), "BLOCKER_EVALUATION_ERROR"));
            }
        }
        return new CloseReadiness(period, results);
    }

    private BillingPeriod requirePeriod(long organizationId, long periodId) {
        var period = periods.findById(organizationId, periodId);
        if (period == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Billing period not found",
                    "The billing period is not available in the current organization.");
        }
        return period;
    }

    public record CloseReadiness(BillingPeriod period, List<CloseBlockerResult> checks) {
        public CloseReadiness {
            checks = List.copyOf(checks);
        }
        public boolean ready() {
            return checks.stream().allMatch(CloseBlockerResult::passed);
        }
    }
}
