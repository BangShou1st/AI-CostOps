package com.aicostops.intelligence.application;

import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns atomic analysis results and durable failure recording after rollback. */
@Service
public class CostIntelligenceRunService {
    private static final Logger LOG = LoggerFactory.getLogger(CostIntelligenceRunService.class);
    private final IntelligenceMapper store;
    private final Clock clock;
    private final CostAnalysisInputService inputs;
    private final TransactionTemplate analysisTransactions;
    private final TransactionTemplate failureTransactions;

    public CostIntelligenceRunService(IntelligenceMapper store, Clock clock, CostAnalysisInputService inputs,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.clock = clock;
        this.inputs = inputs;
        this.analysisTransactions = new TransactionTemplate(transactionManager);
        this.analysisTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.failureTransactions = new TransactionTemplate(transactionManager);
        this.failureTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AnalysisRunOutcome run(long organizationId, LocalDate date, String currency,
            int version, BiConsumer<Long, CostAnalysisInputService.Input> analyze) {
        var captured = new AtomicReference<String>();
        try {
            return analysisTransactions.execute(status -> {
                var now = clock.instant();
                var input = inputs.lockAndCapture(organizationId, currency, date, version);
                var fingerprint = input.fingerprint();
                captured.set(fingerprint);
                store.insertRunIfMissing(organizationId, date, currency, version, fingerprint, now);
                if (store.claimRun(organizationId, date, currency, version, fingerprint, now) != 1) {
                    return new AnalysisRunOutcome(store.findRunId(organizationId, date, currency, version, fingerprint),
                            store.findRunStatus(organizationId, date, currency, version, fingerprint));
                }
                var runId = store.findRunId(organizationId, date, currency, version, fingerprint);
                analyze.accept(runId, input);
                if (store.finishRun(organizationId, date, currency, version, fingerprint,
                        "COMPLETED", null, clock.instant()) != 1) {
                    throw new IllegalStateException("Analysis run lost its RUNNING state");
                }
                return new AnalysisRunOutcome(runId, "COMPLETED");
            });
        } catch (RuntimeException failure) {
            // No durable input identity exists if acquisition failed before the run began.
            if (captured.get() == null) throw failure;
            // An enclosing transaction may still hold the rolled-back attempt's
            // locks. Wait for its rollback before starting the independent write.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            recordFailure(organizationId, date, currency, version, captured.get(), failure);
                        }
                    }
                });
            } else {
                recordFailure(organizationId, date, currency, version, captured.get(), failure);
            }
            throw failure;
        }
    }

    private void recordFailure(long organizationId, LocalDate date, String currency,
            int version, String fingerprint, RuntimeException failure) {
        try {
            failureTransactions.executeWithoutResult(status -> {
                var now = clock.instant();
                // The failed analysis rolled back its initial INSERT as well as
                // all derived results. Recreate its identity, but never overwrite
                // a concurrent attempt that has already completed or failed.
                store.insertRunIfMissing(organizationId, date, currency, version, fingerprint, now);
                if (store.claimRun(organizationId, date, currency, version, fingerprint, now) == 1) {
                    if (store.finishRun(organizationId, date, currency, version, fingerprint,
                            "FAILED", failure.getClass().getSimpleName(), now) != 1) {
                        throw new IllegalStateException("Analysis failure could not be recorded");
                    }
                }
            });
        } catch (RuntimeException recordingFailure) {
            failure.addSuppressed(recordingFailure);
            LOG.error("Could not persist analysis failure for organization {} and date {}",
                    organizationId, date, recordingFailure);
        }
    }
}
