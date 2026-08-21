package com.aicostops.reconciliation.application;

import com.aicostops.budget.application.BillingPeriodReadPort;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.domain.ReconciliationCaseStatus;
import com.aicostops.reconciliation.domain.ReconciliationRun;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class ReconciliationQueryService {

    private static final String PERMISSION_READ = "RECONCILIATION_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodReadPort periods;
    private final ReconciliationMapper mapper;

    public ReconciliationQueryService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodReadPort periods,
            ReconciliationMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.periods = periods;
        this.mapper = mapper;
    }

    public PageResponse<ReconciliationRun> listRuns(
            AuthenticatedUser user, long billingPeriodId, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        requirePeriod(context.organizationId(), billingPeriodId);
        var request = PageRequest.of(page, size);
        var total = mapper.countRunsByPeriod(context.organizationId(), billingPeriodId);
        var items = mapper.selectRunsByPeriod(context.organizationId(), billingPeriodId,
                request.size(), Math.multiplyExact(request.page(), request.size()));
        return PageResponse.of(items, request, total);
    }

    public ReconciliationRun getRun(AuthenticatedUser user, long runId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        var run = mapper.selectRunByIdAndOrganization(context.organizationId(), runId);
        if (run == null) {
            throw notFound("Reconciliation run not found");
        }
        return run;
    }

    public PageResponse<ReconciliationCase> listCases(
            AuthenticatedUser user, long runId, String status, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        if (mapper.selectRunByIdAndOrganization(context.organizationId(), runId) == null) {
            throw notFound("Reconciliation run not found");
        }
        var normalizedStatus = normalizeStatus(status);
        var request = PageRequest.of(page, size);
        var total = mapper.countCasesByRun(context.organizationId(), runId, normalizedStatus);
        var items = mapper.selectCasesByRun(context.organizationId(), runId, normalizedStatus,
                request.size(), Math.multiplyExact(request.page(), request.size()));
        return PageResponse.of(items, request, total);
    }

    public ReconciliationCase getCase(AuthenticatedUser user, long caseId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_READ);
        var result = mapper.selectCaseByIdAndOrganization(context.organizationId(), caseId);
        if (result == null) {
            throw notFound("Reconciliation case not found");
        }
        return result;
    }

    private void requirePeriod(long organizationId, long billingPeriodId) {
        if (periods.findById(organizationId, billingPeriodId) == null) {
            throw notFound("Billing period not found");
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReconciliationCaseStatus.valueOf(status.strip().toUpperCase()).name();
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid reconciliation case status",
                    "status must be OPEN, INVESTIGATING, or RESOLVED.");
        }
    }

    private static DomainException notFound(String title) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                title, "The resource is not available in the current organization.");
    }
}
