package com.aicostops.ingestion.application;

import com.aicostops.evidence.application.EvidencePersistenceService;
import com.aicostops.evidence.application.ObjectStoragePort;
import com.aicostops.ingestion.domain.ImportIssueSeverity;
import com.aicostops.ingestion.infrastructure.ImportAttemptMapper;
import com.aicostops.ingestion.infrastructure.ImportBatchMapper;
import com.aicostops.ingestion.infrastructure.ImportWorkerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Executes one claimed ImportAttempt: inspect -> persist inspection issues ->
 * stream parse -> normalize -> bounded fenced persistence -> fenced finalization.
 *
 * <p>Finalization (success or failure) is lease-fenced: a worker that lost its lease
 * updates zero rows and must stop. Automatic recovery never resurrects the old
 * Attempt; it creates a successor.
 */
@Service
public class ImportAttemptExecutor {

    private final ProviderAdapterRegistry registry;
    private final ImportLeaseService leases;
    private final ImportRawPersistenceService persistence;
    private final ImportAttemptMapper attemptMapper;
    private final ImportBatchMapper batchMapper;
    private final EvidencePersistenceService evidencePersistence;
    private final ObjectStoragePort storage;
    private final ImportWorkerProperties properties;
    private final ImportAttemptFinalizationService finalizer;
    private final ConcurrentHashMap<Long, ImportLeaseService.ImportLease> activeLeases =
            new ConcurrentHashMap<>();

    public ImportAttemptExecutor(
            ProviderAdapterRegistry registry,
            ImportLeaseService leases,
            ImportRawPersistenceService persistence,
            ImportAttemptMapper attemptMapper,
            ImportBatchMapper batchMapper,
            EvidencePersistenceService evidencePersistence,
            ObjectStoragePort storage,
            ImportWorkerProperties properties,
            ImportAttemptFinalizationService finalizer) {
        this.registry = registry;
        this.leases = leases;
        this.persistence = persistence;
        this.attemptMapper = attemptMapper;
        this.batchMapper = batchMapper;
        this.evidencePersistence = evidencePersistence;
        this.storage = storage;
        this.properties = properties;
        this.finalizer = finalizer;
    }

    public void execute(ImportLeaseService.ImportLease lease) {
        activeLeases.put(lease.attemptId(), lease);
        try {
            executeInternal(lease);
        } finally {
            // remove(key, value) never clears a newer execution that reused the id.
            activeLeases.remove(lease.attemptId(), lease);
        }
    }

    /**
     * Renews every currently executing lease from a stable snapshot. A failure for
     * one execution must not prevent the others from being renewed; lease loss is
     * ultimately enforced by the fenced writes of the worker that lost it.
     */
    public void heartbeatActiveExecutions() {
        for (var lease : List.copyOf(activeLeases.values())) {
            try {
                leases.heartbeat(lease.attemptId(), lease.leaseOwner(), lease.leaseVersion());
            } catch (RuntimeException ignored) {
                // One failing renewal must not block the remaining active leases.
            }
        }
    }

    private void executeInternal(ImportLeaseService.ImportLease lease) {
        var attempt = attemptMapper.findById(lease.attemptId());
        if (attempt == null) {
            throw new IllegalStateException("Claimed ImportAttempt must exist");
        }
        var batch = batchMapper.findById(attempt.importBatchId());
        if (batch == null) {
            throw new IllegalStateException("ImportAttempt's ImportBatch must exist");
        }
        var adapter = registry.findByCode(batch.expectedProviderCode())
                .orElseThrow(() -> new IllegalStateException(
                        "No ProviderAdapter registered for " + batch.expectedProviderCode()));
        var evidence = evidencePersistence.findByIdAndOrganization(batch.evidenceId(), batch.organizationId())
                .orElseThrow(() -> new IllegalStateException("ImportBatch Evidence must exist"));
        var source = new EvidenceBackedProviderSource(storage, evidence.objectKey(), evidence.sizeBytes());
        var input = new ProviderInput(source, batch.sourceType(),
                evidence.originalFilename(), evidence.mediaType());

        try {
            var inspection = adapter.inspect(input);
            if (!inspection.issues().isEmpty()) {
                var issueResult = persistence.persistInspectionIssues(lease, inspection.issues());
                if (issueResult.leaseLost()) {
                    return;
                }
            }
            attemptMapper.recordInspection(lease.attemptId(), lease.leaseOwner(), lease.leaseVersion(),
                    inspection.detectedProviderCode(), inspection.schemaFingerprint());
            if (!inspection.compatible() || hasError(inspection.issues())) {
                fail(lease, "SCHEMA_INCOMPATIBLE",
                        "Provider schema is not compatible with the registered adapter.");
                return;
            }
            var sink = new BoundedRecordSink(lease, persistence, properties.persistenceBatchSize(),
                    batch.organizationId(), batch.expectedProviderCode());
            adapter.parse(input, inspection, sink);
            sink.flush();
        } catch (LeaseLostException lost) {
            // Lease lost: stop silently; a successor may already be queued by recovery.
            return;
        } catch (RuntimeException failure) {
            fail(lease, "EXECUTION_FAILED", safeFailureSummary(failure));
            return;
        }

        // Finalization is a separate atomic transaction; its failure propagates so
        // no second (potentially double) finalization is attempted.
        var finished = attemptMapper.findById(lease.attemptId());
        if (finished == null) {
            throw new IllegalStateException("Executing ImportAttempt must remain readable");
        }
        if (finished.errorCount() > 0) {
            fail(lease, "DATA_ERRORS", "Provider records contained ERROR issues.");
        } else {
            succeed(lease);
        }
    }

    private boolean hasError(List<ImportIssueDraft> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == ImportIssueSeverity.ERROR);
    }

    private void succeed(ImportLeaseService.ImportLease lease) {
        finalizer.completeSuccess(lease);
    }

    private void fail(ImportLeaseService.ImportLease lease, String errorCode, String errorSummary) {
        finalizer.completeFailure(lease, errorCode, errorSummary);
    }

    /**
     * Stable, secret-free failure summary: only the exception category is recorded,
     * never the raw provider message.
     */
    private String safeFailureSummary(RuntimeException failure) {
        return "Provider import execution failed (" + failure.getClass().getSimpleName() + ").";
    }

    private static final class BoundedRecordSink implements ProviderRecordSink {

        private final ImportLeaseService.ImportLease lease;
        private final ImportRawPersistenceService persistence;
        private final int batchSize;
        private final long orgId;
        private final String providerCode;
        private final List<NormalizedProviderRecord> buffer = new ArrayList<>();
        private boolean leaseLost;

        private BoundedRecordSink(
                ImportLeaseService.ImportLease lease,
                ImportRawPersistenceService persistence,
                int batchSize,
                long orgId,
                String providerCode) {
            this.lease = lease;
            this.persistence = persistence;
            this.batchSize = batchSize;
            this.orgId = orgId;
            this.providerCode = providerCode;
        }

        @Override
        public void accept(NormalizedProviderRecord record) {
            if (leaseLost) {
                throw new LeaseLostException();
            }
            buffer.add(record);
            if (buffer.size() >= batchSize) {
                flush();
            }
        }

        private void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            var result = persistence.persist(lease, List.copyOf(buffer), orgId, providerCode);
            buffer.clear();
            if (result.leaseLost()) {
                leaseLost = true;
                throw new LeaseLostException();
            }
        }
    }

    /** Signals that the worker lost lease ownership mid-execution. */
    static final class LeaseLostException extends RuntimeException {
    }
}
