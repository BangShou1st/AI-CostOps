package com.aicostops.ingestion.application;

import com.aicostops.ingestion.infrastructure.ImportAttemptMapper;
import com.aicostops.ingestion.infrastructure.ImportBatchMapper;
import com.aicostops.ingestion.infrastructure.ImportWorkerProperties;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL-backed worker claim, heartbeat and lease fencing.
 *
 * <p>Claim is one short transaction: {@code FOR UPDATE SKIP LOCKED} picks the next
 * queued Attempt, then the Attempt is marked RUNNING with a lease computed from the
 * database clock and the parent Batch becomes PROCESSING. Heartbeat and ownership
 * verification require the exact {@code (attemptId, workerId, leaseVersion)} tuple
 * and an unexpired lease; zero affected rows means the worker lost ownership.
 */
@Service
public class ImportLeaseService {

    private final ImportAttemptMapper attemptMapper;
    private final ImportBatchMapper batchMapper;
    private final ImportWorkerProperties properties;
    private final TransactionTemplate transactions;

    public ImportLeaseService(
            ImportAttemptMapper attemptMapper,
            ImportBatchMapper batchMapper,
            ImportWorkerProperties properties,
            PlatformTransactionManager transactionManager) {
        this.attemptMapper = attemptMapper;
        this.batchMapper = batchMapper;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Optional<ImportLease> claimNext(String workerId) {
        var leaseMicros = properties.leaseDuration().toNanos() / 1000;
        return transactions.execute(status -> {
            var attempt = attemptMapper.claimNextQueued();
            if (attempt == null) {
                return Optional.empty();
            }
            attemptMapper.markRunning(attempt.id(), workerId, leaseMicros);
            batchMapper.updateStatus(attempt.importBatchId(), "PROCESSING", Instant.now());
            var claimed = attemptMapper.findById(attempt.id());
            if (claimed == null) {
                throw new IllegalStateException("Claimed ImportAttempt must remain readable");
            }
            return Optional.of(new ImportLease(
                    claimed.id(), claimed.importBatchId(), claimed.leaseOwner(), claimed.leaseVersion()));
        });
    }

    public boolean heartbeat(long attemptId, String workerId, long leaseVersion) {
        var leaseMicros = properties.leaseDuration().toNanos() / 1000;
        var updated = transactions.execute(status ->
                attemptMapper.renewLease(attemptId, workerId, leaseVersion, leaseMicros));
        return updated != null && updated == 1;
    }

    public boolean ownsLease(long attemptId, String workerId, long leaseVersion) {
        return attemptMapper.countOwnedRunning(attemptId, workerId, leaseVersion) == 1;
    }

    /**
     * Recovers at most one expired RUNNING Attempt: the old Attempt becomes
     * FAILED(WORKER_LEASE_EXPIRED) and a successor LEASE_RECOVERY Attempt is queued,
     * unless the automatic recovery budget is exhausted (Batch then FAILED).
     */
    public Optional<RecoveredLease> recoverExpiredLease() {
        return transactions.execute(status -> {
            var expired = attemptMapper.findExpiredRunning();
            if (expired == null) {
                return Optional.empty();
            }
            var batch = batchMapper.findByIdForUpdate(expired.importBatchId());
            if (batch == null) {
                throw new IllegalStateException("Expired Attempt's ImportBatch must exist");
            }
            attemptMapper.failRunningAttempt(expired.id(), "WORKER_LEASE_EXPIRED",
                    "Worker lease expired before execution finished.");
            var now = Instant.now();
            var recoveryCount = attemptMapper.countLeaseRecoveriesByBatch(batch.id());
            if (recoveryCount >= properties.maxLeaseRecoveries()) {
                batchMapper.updateStatus(batch.id(), "FAILED", now);
                return Optional.empty();
            }
            attemptMapper.insertQueued(batch.id(), expired.attemptNo() + 1, "LEASE_RECOVERY",
                    expired.id(), expired.parserVersion(), now, now);
            batchMapper.updateStatus(batch.id(), "PENDING", now);
            var successor = attemptMapper.findById(attemptMapper.lastInsertId());
            if (successor == null) {
                throw new IllegalStateException("Recovery successor must be readable");
            }
            return Optional.of(new RecoveredLease(successor.id(), successor.importBatchId()));
        });
    }

    public record ImportLease(long attemptId, long importBatchId, String leaseOwner, long leaseVersion) {
    }

    public record RecoveredLease(long attemptId, long importBatchId) {
    }
}
