package com.aicostops.ingestion.application;

import com.aicostops.ingestion.infrastructure.ImportAttemptMapper;
import com.aicostops.ingestion.infrastructure.ImportBatchMapper;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomic Attempt + Batch finalization.
 *
 * <p>Each completion runs in one short transaction: the fenced Attempt status write
 * and the Batch status write commit or roll back together. If the Attempt write
 * affects zero rows the worker lost its lease and nothing is changed; if the Batch
 * write fails the whole transaction rolls back so a half-finalized
 * {@code SUCCEEDED + PROCESSING} state can never exist.
 */
@Service
public class ImportAttemptFinalizationService {

    private final ImportAttemptMapper attemptMapper;
    private final ImportBatchMapper batchMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ImportAttemptFinalizationService(
            ImportAttemptMapper attemptMapper,
            ImportBatchMapper batchMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.attemptMapper = attemptMapper;
        this.batchMapper = batchMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Returns true when the Attempt and Batch were finalized; false when lease was lost. */
    public boolean completeSuccess(ImportLeaseService.ImportLease lease) {
        return transactions.execute(status -> {
            if (attemptMapper.finishSucceeded(
                    lease.attemptId(), lease.leaseOwner(), lease.leaseVersion()) != 1) {
                return false;
            }
            if (batchMapper.updateStatus(lease.importBatchId(), "READY_FOR_REVIEW", clock.instant()) != 1) {
                throw new IllegalStateException("ImportBatch finalization must update exactly one row");
            }
            return true;
        });
    }

    /** Returns true when the Attempt and Batch were finalized; false when lease was lost. */
    public boolean completeFailure(
            ImportLeaseService.ImportLease lease, String errorCode, String errorSummary) {
        return transactions.execute(status -> {
            if (attemptMapper.finishFailed(lease.attemptId(), lease.leaseOwner(), lease.leaseVersion(),
                    errorCode, errorSummary) != 1) {
                return false;
            }
            if (batchMapper.updateStatus(lease.importBatchId(), "FAILED", clock.instant()) != 1) {
                throw new IllegalStateException("ImportBatch finalization must update exactly one row");
            }
            return true;
        });
    }
}
