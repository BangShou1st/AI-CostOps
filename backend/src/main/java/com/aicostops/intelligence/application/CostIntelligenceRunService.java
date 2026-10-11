package com.aicostops.intelligence.application;

import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.util.function.LongConsumer;
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
    private final TransactionTemplate analysisTransactions;
    private final TransactionTemplate failureTransactions;

    public CostIntelligenceRunService(IntelligenceMapper store, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.store = store;
        this.clock = clock;
        this.analysisTransactions = new TransactionTemplate(transactionManager);
        this.failureTransactions = new TransactionTemplate(transactionManager);
        this.failureTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AnalysisRunOutcome run(long organizationId, LocalDate date, String currency,
            int version, LongConsumer analyze) {
        try {
            return analysisTransactions.execute(status -> {
                var now = clock.instant();
                store.insertRunIfMissing(organizationId, date, currency, version, now);
                if (store.claimRun(organizationId, date, currency, version, now) != 1) {
                    return new AnalysisRunOutcome(store.findRunId(organizationId, date, currency, version),
                            store.findRunStatus(organizationId, date, currency, version));
                }
                var runId = store.findRunId(organizationId, date, currency, version);
                analyze.accept(runId);
                if (store.finishRun(organizationId, date, currency, version,
                        "COMPLETED", null, clock.instant()) != 1) {
                    throw new IllegalStateException("Analysis run lost its RUNNING state");
                }
                return new AnalysisRunOutcome(runId, "COMPLETED");
            });
        } catch (RuntimeException failure) {
            // An enclosing transaction may still hold the rolled-back attempt's
            // locks. Wait for its rollback before starting the independent write.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            recordFailure(organizationId, date, currency, version, failure);
                        }
                    }
                });
            } else {
                recordFailure(organizationId, date, currency, version, failure);
            }
            throw failure;
        }
    }

    private void recordFailure(long organizationId, LocalDate date, String currency,
            int version, RuntimeException failure) {
        try {
            failureTransactions.executeWithoutResult(status -> {
                var now = clock.instant();
                // The failed analysis rolled back its initial INSERT as well as
                // all derived results. Recreate its identity, but never overwrite
                // a concurrent attempt that has already completed or failed.
                store.insertRunIfMissing(organizationId, date, currency, version, now);
                if (store.claimRun(organizationId, date, currency, version, now) == 1) {
                    if (store.finishRun(organizationId, date, currency, version,
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
