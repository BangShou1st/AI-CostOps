package com.aicostops.reconciliation.application;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounded recovery sweep for reconciliation runs abandoned by a crashed process.
 *
 * <p>A run is inserted as RUNNING inside the start transaction, so a process
 * death between start and finalization leaves an active run behind forever.
 * This sweep converges such runs through {@link ReconciliationRunService}:
 * row-lock, re-check against the cutoff, CAS to FAILED. Completion remains the
 * authority for a live run, so a slow but healthy evaluation is never failed
 * before its timeout. Multi-instance deployments are safe because the CAS only
 * matches a row that is still RUNNING.
 */
@Component
@ConditionalOnProperty(name = "aicostops.reconciliation.stale-run-recovery-enabled",
        havingValue = "true", matchIfMissing = true)
public final class ReconciliationRunRecoveryScheduler {

    private static final Logger LOG =
            LoggerFactory.getLogger(ReconciliationRunRecoveryScheduler.class);

    private final ReconciliationRunService runs;
    private final Duration staleAfter;
    private final int batchSize;

    public ReconciliationRunRecoveryScheduler(
            ReconciliationRunService runs,
            @Value("${aicostops.reconciliation.stale-run-timeout:30m}") Duration staleAfter,
            @Value("${aicostops.reconciliation.stale-run-sweep-batch-size:100}") int batchSize) {
        this.runs = runs;
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${aicostops.reconciliation.stale-run-sweep-interval:5m}",
            initialDelayString = "${aicostops.reconciliation.stale-run-sweep-initial-delay:30s}")
    public void sweepStaleRuns() {
        try {
            var recovered = runs.failStaleRunningRuns(staleAfter, batchSize);
            if (recovered > 0) {
                LOG.info("Converged {} stale RUNNING reconciliation run(s)", recovered);
            } else {
                LOG.debug("No stale RUNNING reconciliation runs to converge");
            }
        } catch (RuntimeException failure) {
            // A datasource outage must not kill the scheduled task; the next
            // fixed-delay sweep retries the same bounded scan.
            LOG.warn("Stale reconciliation run sweep failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}
