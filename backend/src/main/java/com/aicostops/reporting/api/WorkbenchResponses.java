package com.aicostops.reporting.api;

import com.aicostops.reporting.application.WorkbenchReadModels;
import com.aicostops.reporting.application.WorkbenchReadModels.WorkbenchView;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Wire representation of the workbench: identifiers are decimal strings and
 * amounts keep their fixed 8-decimal string form; sections the caller has no
 * ORG grant for are omitted from the payload entirely.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class WorkbenchResponses {

    private WorkbenchResponses() {
    }

    public record PeriodResponse(
            String billingPeriodId,
            Instant periodStart,
            Instant periodEnd,
            String status) {
    }

    public record ProviderCostLineResponse(
            String providerCode,
            String currency,
            String totalAmount,
            long chargeCount) {
    }

    public record ProjectCostLineResponse(
            String projectId,
            String projectName,
            String currency,
            String totalAmount) {
    }

    public record BudgetVarianceLineResponse(
            String budgetId,
            String scopeType,
            String scopeId,
            String currency,
            String totalAmount,
            String actualAmount,
            String committedAmount,
            String availableAmount,
            boolean overBudget) {
    }

    public record CurrencyAmountResponse(
            String currency,
            String amount,
            long chargeCount) {
    }

    public record DuplicateCandidatesResponse(long openCount) {
    }

    public record PendingApprovalsResponse(long submittedCount, long needsInfoCount) {
    }

    public record OpenReconciliationsResponse(long activeRunCount, long openCaseCount) {
    }

    public record CloseStatusResponse(String status, boolean closing, boolean closed) {
    }

    public record WorkbenchResponse(
            PeriodResponse period,
            List<ProviderCostLineResponse> costByProvider,
            List<ProjectCostLineResponse> costByProject,
            List<BudgetVarianceLineResponse> budgetVariance,
            List<CurrencyAmountResponse> unallocatedCharges,
            DuplicateCandidatesResponse duplicateCandidates,
            PendingApprovalsResponse pendingApprovals,
            OpenReconciliationsResponse openReconciliations,
            CloseStatusResponse closeStatus) {

        public static WorkbenchResponse from(WorkbenchView view) {
            return new WorkbenchResponse(
                    view.period() == null ? null
                            : new PeriodResponse(Long.toString(view.period().billingPeriodId()),
                                    view.period().periodStart(), view.period().periodEnd(),
                                    view.period().status()),
                    view.costByProvider().stream().map(line ->
                            new ProviderCostLineResponse(line.providerCode(), line.currency(),
                                    line.totalAmount(), line.chargeCount())).toList(),
                    view.costByProject().stream().map(line ->
                            new ProjectCostLineResponse(Long.toString(line.projectId()),
                                    line.projectName(), line.currency(), line.totalAmount()))
                            .toList(),
                    view.budgetVariance().stream().map(line ->
                            new BudgetVarianceLineResponse(Long.toString(line.budgetId()),
                                    line.scopeType(), Long.toString(line.scopeId()),
                                    line.currency(), line.totalAmount(), line.actualAmount(),
                                    line.committedAmount(), line.availableAmount(),
                                    line.overBudget())).toList(),
                    view.unallocatedCharges().stream().map(line ->
                            new CurrencyAmountResponse(line.currency(), line.amount(),
                                    line.chargeCount())).toList(),
                    view.duplicateCandidates() == null ? null
                            : new DuplicateCandidatesResponse(
                                    view.duplicateCandidates().openCount()),
                    view.pendingApprovals() == null ? null
                            : new PendingApprovalsResponse(
                                    view.pendingApprovals().submittedCount(),
                                    view.pendingApprovals().needsInfoCount()),
                    view.openReconciliations() == null ? null
                            : new OpenReconciliationsResponse(
                                    view.openReconciliations().activeRunCount(),
                                    view.openReconciliations().openCaseCount()),
                    view.closeStatus() == null ? null
                            : new CloseStatusResponse(view.closeStatus().status(),
                                    view.closeStatus().closing(), view.closeStatus().closed()));
        }
    }
}
