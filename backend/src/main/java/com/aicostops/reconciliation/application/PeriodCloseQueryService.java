package com.aicostops.reconciliation.application;

import com.aicostops.budget.application.BillingPeriodReadPort;
import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.reconciliation.application.PeriodCloseReadModels.PeriodCloseView;
import com.aicostops.reconciliation.infrastructure.PeriodCloseMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
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
    private final PeriodCloseMapper closeMapper;

    public PeriodCloseQueryService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodReadPort periods,
            CloseBlockerRegistry blockers,
            PeriodCloseMapper closeMapper) {
        this.authorizationContexts = authorizationContexts;
        this.periods = periods;
        this.blockers = blockers;
        this.closeMapper = closeMapper;
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

    public PageResponse<PeriodCloseView> listRuns(
            AuthenticatedUser user, long periodId, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        var period = requirePeriod(context.organizationId(), periodId);
        var request = PageRequest.of(page, size);
        var total = closeMapper.countRunsByPeriod(context.organizationId(), periodId);
        var runs = closeMapper.selectRunsByPeriod(context.organizationId(), periodId,
                request.size(), Math.multiplyExact(request.page(), request.size()));
        var views = runs.stream()
                .map(run -> new PeriodCloseView(period, run,
                        closeMapper.selectChecksByRun(context.organizationId(), run.id())))
                .toList();
        return PageResponse.of(views, request, total);
    }

    public PeriodCloseView getRun(AuthenticatedUser user, long periodId, long runId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        var period = requirePeriod(context.organizationId(), periodId);
        var run = closeMapper.selectRunByIdAndOrganization(context.organizationId(), runId);
        if (run == null || run.billingPeriodId() != periodId) {
            throw notFound("Close run not found");
        }
        return new PeriodCloseView(period, run,
                closeMapper.selectChecksByRun(context.organizationId(), runId));
    }

    private BillingPeriod requirePeriod(long organizationId, long periodId) {
        var period = periods.findById(organizationId, periodId);
        if (period == null) {
            throw notFound("Billing period not found");
        }
        return period;
    }

    private static DomainException notFound(String title) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                title, "The resource is not available in the current organization.");
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
