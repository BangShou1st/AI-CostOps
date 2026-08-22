package com.aicostops.reporting.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.reporting.application.WorkbenchReadModels.BudgetVarianceLine;
import com.aicostops.reporting.application.WorkbenchReadModels.CloseStatus;
import com.aicostops.reporting.application.WorkbenchReadModels.CurrencyAmount;
import com.aicostops.reporting.application.WorkbenchReadModels.DuplicateCandidates;
import com.aicostops.reporting.application.WorkbenchReadModels.OpenReconciliations;
import com.aicostops.reporting.application.WorkbenchReadModels.PendingApprovals;
import com.aicostops.reporting.application.WorkbenchReadModels.PeriodSummary;
import com.aicostops.reporting.application.WorkbenchReadModels.WorkbenchView;
import com.aicostops.reporting.infrastructure.WorkbenchQueryMapper;
import com.aicostops.reporting.infrastructure.WorkbenchQueryMapper.BudgetRow;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Aggregated read-only workbench for one organization and one billing period
 * (explicit id or the organization's latest period). Every section is filled
 * only when the caller holds that section's permission with ORG scope —
 * aggregated numbers cannot be row-filtered, so narrower grants must not see
 * them. Responses are served cache-aside through {@link DashboardCachePort}
 * with a short TTL; a cold or failed cache only costs MySQL reads.
 */
@Service
public class WorkbenchQueryService {

    private static final String PERMISSION_PERIOD_READ = "PERIOD_READ";
    private static final String PERMISSION_COST_READ = "COST_READ";
    private static final String PERMISSION_BUDGET_READ = "BUDGET_READ";
    private static final String PERMISSION_ALLOCATION_READ = "ALLOCATION_READ";
    private static final String PERMISSION_DUPLICATE_REVIEW = "DUPLICATE_REVIEW";
    private static final String PERMISSION_EXPENSE_REVIEW = "EXPENSE_REVIEW";
    private static final String PERMISSION_RECONCILIATION_READ = "RECONCILIATION_READ";

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int LINE_LIMIT = 50;
    private static final int BUDGET_LIMIT = 200;

    private final AuthorizationContextService authorizationContexts;
    private final WorkbenchQueryMapper mapper;
    private final DashboardCachePort cache;

    public WorkbenchQueryService(
            AuthorizationContextService authorizationContexts,
            WorkbenchQueryMapper mapper,
            DashboardCachePort cache) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.cache = cache;
    }

    public WorkbenchView get(AuthenticatedUser user, Long billingPeriodId) {
        var context = authorizationContexts.current(user);
        var orgPermissions = orgPermissions(context);
        var cacheKey = "workbench:" + context.organizationId() + ":"
                + (billingPeriodId == null ? "current" : billingPeriodId);
        var cached = cache.read(cacheKey, WorkbenchView.class);
        if (cached != null) {
            return cached;
        }
        var view = assemble(context, orgPermissions, billingPeriodId);
        cache.write(cacheKey, view, CACHE_TTL);
        return view;
    }

    private WorkbenchView assemble(AuthorizationContext context,
            Set<String> permissions, Long billingPeriodId) {
        var organizationId = context.organizationId();
        var period = resolvePeriod(organizationId, billingPeriodId);

        var periodSummary = period != null && permissions.contains(PERMISSION_PERIOD_READ)
                ? new PeriodSummary(period.billingPeriodId(), period.periodStart(),
                        period.periodEnd(), period.status())
                : null;

        var costByProvider = period != null && permissions.contains(PERMISSION_COST_READ)
                ? mapper.sumChargesByProvider(organizationId, period.periodStart(),
                        period.periodEnd(), LINE_LIMIT)
                : List.<WorkbenchReadModels.ProviderCostLine>of();
        var costByProject = period != null && permissions.contains(PERMISSION_COST_READ)
                ? mapper.sumAllocationsByProject(organizationId, period.periodStart(),
                        period.periodEnd(), LINE_LIMIT)
                : List.<WorkbenchReadModels.ProjectCostLine>of();
        var unallocated = period != null && permissions.contains(PERMISSION_ALLOCATION_READ)
                ? mapper.sumUnallocatedByCurrency(organizationId, period.periodStart(),
                        period.periodEnd())
                : List.<CurrencyAmount>of();

        var budgetVariance = period != null && permissions.contains(PERMISSION_BUDGET_READ)
                ? budgetVariance(organizationId, period.billingPeriodId())
                : List.<BudgetVarianceLine>of();

        var duplicateCandidates = permissions.contains(PERMISSION_DUPLICATE_REVIEW)
                ? new DuplicateCandidates(mapper.countOpenDuplicateCandidates(organizationId))
                : null;
        var pendingApprovals = permissions.contains(PERMISSION_EXPENSE_REVIEW)
                ? pendingApprovals(organizationId)
                : null;
        var openReconciliations = permissions.contains(PERMISSION_RECONCILIATION_READ)
                ? new OpenReconciliations(
                        mapper.countActiveReconciliationRuns(organizationId),
                        mapper.countOpenReconciliationCases(organizationId))
                : null;
        var closeStatus = period != null && permissions.contains(PERMISSION_PERIOD_READ)
                ? closeStatus(period.status())
                : null;

        return new WorkbenchView(periodSummary, costByProvider, costByProject, budgetVariance,
                unallocated, duplicateCandidates, pendingApprovals, openReconciliations,
                closeStatus);
    }

    private PeriodSummary resolvePeriod(long organizationId, Long billingPeriodId) {
        if (billingPeriodId == null) {
            return mapper.selectLatestPeriod(organizationId);
        }
        var period = mapper.selectPeriodById(organizationId, billingPeriodId);
        if (period == null) {
            throw validation("The requested billing period does not exist "
                    + "in the current organization.");
        }
        return period;
    }

    private List<BudgetVarianceLine> budgetVariance(long organizationId, long periodId) {
        return mapper.selectBudgetsForPeriod(organizationId, periodId, BUDGET_LIMIT).stream()
                .map(WorkbenchQueryService::varianceOf)
                .toList();
    }

    private static BudgetVarianceLine varianceOf(BudgetRow row) {
        var total = new BigDecimal(row.totalAmount());
        var actual = new BigDecimal(row.actualAmount());
        var committed = new BigDecimal(row.committedAmount());
        var available = total.subtract(actual).subtract(committed);
        return new BudgetVarianceLine(row.budgetId(), row.scopeType(), row.scopeId(),
                row.currency(), row.totalAmount(), row.actualAmount(), row.committedAmount(),
                available.toPlainString(), available.signum() < 0);
    }

    private PendingApprovals pendingApprovals(long organizationId) {
        var counts = mapper.countExpenseClaimsByStatus(organizationId);
        long submitted = 0;
        long needsInfo = 0;
        for (var count : counts) {
            if ("SUBMITTED".equals(count.status())) {
                submitted = count.itemCount();
            } else if ("NEEDS_INFO".equals(count.status())) {
                needsInfo = count.itemCount();
            }
        }
        return new PendingApprovals(submitted, needsInfo);
    }

    private static CloseStatus closeStatus(String status) {
        return new CloseStatus(status, "CLOSING".equals(status), "CLOSED".equals(status));
    }

    private static Set<String> orgPermissions(AuthorizationContext context) {
        return context.grants().stream()
                .filter(grant -> grant.scopeType() == ScopeType.ORG)
                .map(ScopedPermissionGrant::permissionCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid workbench request", detail);
    }
}
