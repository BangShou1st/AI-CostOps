package com.aicostops.reporting.application;

import java.time.Instant;
import java.util.List;

/**
 * Read-only workbench view models. Amounts are fixed-scale (8) decimal
 * strings grouped per currency; no section ever sums across currencies.
 * Sections are populated only when the caller holds the matching ORG read
 * grant, so absent sections are represented by empty lists rather than
 * fabricated zeros.
 */
public final class WorkbenchReadModels {

    private WorkbenchReadModels() {
    }

    public record PeriodSummary(
            long billingPeriodId,
            Instant periodStart,
            Instant periodEnd,
            String status) {
    }

    public record ProviderCostLine(
            String providerCode,
            String currency,
            String totalAmount,
            long chargeCount) {
    }

    public record ProjectCostLine(
            long projectId,
            String projectName,
            String currency,
            String totalAmount) {
    }

    public record BudgetVarianceLine(
            long budgetId,
            String scopeType,
            long scopeId,
            String currency,
            String totalAmount,
            String actualAmount,
            String committedAmount,
            String availableAmount,
            boolean overBudget) {
    }

    public record CurrencyAmount(
            String currency,
            String amount,
            long chargeCount) {
    }

    public record DuplicateCandidates(long openCount) {
    }

    public record PendingApprovals(long submittedCount, long needsInfoCount) {
    }

    public record OpenReconciliations(long activeRunCount, long openCaseCount) {
    }

    /**
     * Close status of the displayed period; {@code status} mirrors
     * BillingPeriodStatus and the booleans are convenience projections.
     */
    public record CloseStatus(String status, boolean closing, boolean closed) {
    }

    public record WorkbenchView(
            PeriodSummary period,
            List<ProviderCostLine> costByProvider,
            List<ProjectCostLine> costByProject,
            List<BudgetVarianceLine> budgetVariance,
            List<CurrencyAmount> unallocatedCharges,
            DuplicateCandidates duplicateCandidates,
            PendingApprovals pendingApprovals,
            OpenReconciliations openReconciliations,
            CloseStatus closeStatus) {
    }
}
