package com.aicostops.intelligence.application;

/**
 * Outcome of one analysis attempt (M18 V3).
 *
 * <p>Deliberately independent of the service that produces it so the run transaction owner and
 * the analysis orchestration can share one result type without either depending on the other's
 * source. A run that converged on an existing PENDING/RUNNING/COMPLETED/FAILED row returns that
 * row's persisted status instead of recomputing.
 */
public record AnalysisRunOutcome(Long runId, String status) {
}
