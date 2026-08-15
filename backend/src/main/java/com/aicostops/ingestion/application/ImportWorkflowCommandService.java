package com.aicostops.ingestion.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.ingestion.domain.ImportAttempt;
import com.aicostops.ingestion.domain.ImportAttemptStatus;
import com.aicostops.ingestion.domain.ImportBatch;
import com.aicostops.ingestion.domain.ImportBatchStatus;
import com.aicostops.ingestion.infrastructure.ImportAttemptMapper;
import com.aicostops.ingestion.infrastructure.ImportBatchMapper;
import com.aicostops.ingestion.infrastructure.ImportWorkflowQueryMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent Import workflow commands. Both commands follow the established
 * {@code ImportAttempt -> ImportBatch} lock order, reserve/replay their
 * Idempotency-Key inside the same transaction, and append exactly one secret-free
 * audit event per committed command.
 *
 * <p>Concurrent commands on one Batch can hit MySQL's classic gap-lock/insert-
 * intention deadlock (the locking latest-Attempt scan reserves the gap the
 * successor insert needs). InnoDB rolls one side back; a bounded retry lets that
 * side re-read the winner's committed state and produce the correct 409.
 */
@Service
public class ImportWorkflowCommandService {

    private static final int DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ImportBatchMapper batchMapper;
    private final ImportAttemptMapper attemptMapper;
    private final ImportWorkflowQueryMapper queryMapper;
    private final ImportCommandIdempotency idempotency;
    private final ImportWorkflowAuditPort audit;
    private final ImportCommandResponseSerializer responseSerializer;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ImportWorkflowCommandService(
            AuthorizationContextService authorizationContexts,
            ImportBatchMapper batchMapper,
            ImportAttemptMapper attemptMapper,
            ImportWorkflowQueryMapper queryMapper,
            ImportCommandIdempotency idempotency,
            ImportWorkflowAuditPort audit,
            ImportCommandResponseSerializer responseSerializer,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.queryMapper = queryMapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseSerializer = responseSerializer;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Manual retry: FAILED / CANCELED batches gain one new QUEUED
     * {@code MANUAL_RETRY} Attempt whose predecessor is the latest Attempt and
     * whose {@code attempt_no} is {@code latest + 1}; the Batch returns to
     * PENDING in the same transaction. Old lineage is never touched.
     */
    public ImportSummary retry(AuthenticatedUser user, long importId, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "IMPORT_RETRY");
        ImportCommandIdempotency.validateKey(idempotencyKey);
        preflightOrgScopedBatch(context.organizationId(), importId);

        var requestHash = ImportCommandIdempotency.requestHash(
                ImportCommandIdempotency.OPERATION_RETRY, context.organizationId(),
                context.organizationMemberId(), importId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(), context.organizationMemberId(),
                    ImportCommandIdempotency.OPERATION_RETRY, idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseSerializer.importDetailFromJson(decision.responseBody());
            }

            var latest = attemptMapper.findLatestByBatchForUpdate(importId);
            var batch = batchMapper.findByIdForUpdate(importId);
            if (batch == null || batch.organizationId() != context.organizationId()) {
                throw notFound();
            }
            requireRetryable(batch, latest);
            if (latest == null) {
                throw new IllegalStateException("A retryable ImportBatch must have a latest Attempt");
            }

            var now = clock.instant();
            attemptMapper.insertQueued(importId, latest.attemptNo() + 1, "MANUAL_RETRY",
                    latest.id(), batch.parserVersion(), now, now);
            var newAttemptId = attemptMapper.lastInsertId();
            if (batchMapper.updateStatus(importId, "PENDING", now) != 1) {
                throw new IllegalStateException("ImportBatch retry must update exactly one row");
            }
            audit.importRetried(context.organizationId(), context.userId(), importId,
                    latest.id(), newAttemptId, batch.status().name());

            var detail = currentDetail(context.organizationId(), importId);
            idempotency.finalize(decision.id(), 200, responseSerializer.importDetailJson(detail));
            return detail;
        }));
    }

    private <T> T executeWithDeadlockRetry(Supplier<T> operation) {
        for (var attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DeadlockLoserDataAccessException deadlock) {
                if (attempt >= DEADLOCK_RETRIES) {
                    throw deadlock;
                }
            }
        }
    }

    /**
     * Cooperative cancel: legal only for {@code PENDING + QUEUED} or
     * {@code PROCESSING + RUNNING}. The latest Attempt becomes CANCELED with
     * {@code finished_at} set and its active lease cleared; the Batch becomes
     * CANCELED in the same transaction. Worker threads are never interrupted —
     * the existing lease/fencing model makes every later worker write fail.
     */
    public ImportSummary cancel(AuthenticatedUser user, long importId, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "IMPORT_CANCEL");
        ImportCommandIdempotency.validateKey(idempotencyKey);
        preflightOrgScopedBatch(context.organizationId(), importId);

        var requestHash = ImportCommandIdempotency.requestHash(
                ImportCommandIdempotency.OPERATION_CANCEL, context.organizationId(),
                context.organizationMemberId(), importId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(), context.organizationMemberId(),
                    ImportCommandIdempotency.OPERATION_CANCEL, idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseSerializer.importDetailFromJson(decision.responseBody());
            }

            var latest = attemptMapper.findLatestByBatchForUpdate(importId);
            var batch = batchMapper.findByIdForUpdate(importId);
            if (batch == null || batch.organizationId() != context.organizationId()) {
                throw notFound();
            }
            requireCancelable(batch, latest);

            var now = clock.instant();
            if (attemptMapper.cancelQueuedOrRunning(latest.id(), now) != 1) {
                throw new IllegalStateException("ImportAttempt cancellation must update exactly one row");
            }
            if (batchMapper.updateStatus(importId, "CANCELED", now) != 1) {
                throw new IllegalStateException("ImportBatch cancellation must update exactly one row");
            }
            audit.importCanceled(context.organizationId(), context.userId(), importId,
                    latest.id(), latest.status().name(), batch.status().name());

            var detail = currentDetail(context.organizationId(), importId);
            idempotency.finalize(decision.id(), 200, responseSerializer.importDetailJson(detail));
            return detail;
        }));
    }

    private void requireCancelable(ImportBatch batch, ImportAttempt latest) {
        var pendingQueued = batch.status() == ImportBatchStatus.PENDING
                && latest != null && latest.status() == ImportAttemptStatus.QUEUED;
        var processingRunning = batch.status() == ImportBatchStatus.PROCESSING
                && latest != null && latest.status() == ImportAttemptStatus.RUNNING;
        if (!pendingQueued && !processingRunning) {
            throw stateConflict("Import cannot be canceled",
                    "Only a PENDING import with a queued attempt or a PROCESSING import "
                            + "with a running attempt can be canceled.");
        }
    }

    private void requireRetryable(ImportBatch batch, ImportAttempt latest) {
        if (batch.status() != ImportBatchStatus.FAILED && batch.status() != ImportBatchStatus.CANCELED) {
            throw stateConflict("Import cannot be retried",
                    "Only FAILED or CANCELED imports can be retried.");
        }
        if (latest != null && (latest.status() == ImportAttemptStatus.QUEUED
                || latest.status() == ImportAttemptStatus.RUNNING)) {
            throw stateConflict("Import is already active",
                    "An import with an active queued or running attempt cannot be retried.");
        }
    }

    private void preflightOrgScopedBatch(long organizationId, long importId) {
        var batch = batchMapper.findByIdAndOrganization(importId, organizationId);
        if (batch == null) {
            throw notFound();
        }
    }

    private ImportSummary currentDetail(long organizationId, long importId) {
        var row = queryMapper.findImportByIdAndOrganization(importId, organizationId);
        if (row == null) {
            throw new IllegalStateException("A just-mutated ImportBatch must be readable");
        }
        return ImportWorkflowQueryService.mapImportSummary(row);
    }

    private static DomainException stateConflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT, title, detail);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Import not found", "The import is not available in the current organization.");
    }
}
