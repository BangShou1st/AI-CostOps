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
import com.aicostops.observability.AiCostOpsMetrics;
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
    private final ImportCloseAdmissionPort closeAdmission;
    private final AiCostOpsMetrics metrics;
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
            ImportCloseAdmissionPort closeAdmission,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.queryMapper = queryMapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.responseSerializer = responseSerializer;
        this.closeAdmission = closeAdmission;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

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

            // Retry only creates a new unconfirmed attempt and cannot change
            // confirmed external truth. Keep the organization-level workflow
            // admission, while the later Confirm fences canonical periods.
            closeAdmission.lockAndRequireNoClosingPeriod(context.organizationId());
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

            // Cancel only removes open work, so it remains legal during CLOSING.
            var latest = attemptMapper.findLatestByBatchForUpdate(importId);
            var batch = batchMapper.findByIdForUpdate(importId);
            if (batch == null || batch.organizationId() != context.organizationId()) {
                throw notFound();
            }
            requireCancelable(batch, latest);

            var now = clock.instant();
            var activeAttempt = latest.status() == ImportAttemptStatus.QUEUED
                    || latest.status() == ImportAttemptStatus.RUNNING;
            if (activeAttempt && attemptMapper.cancelQueuedOrRunning(latest.id(), now) != 1) {
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

    public ImportSummary confirm(AuthenticatedUser user, long importId, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "IMPORT_CONFIRM");
        ImportCommandIdempotency.validateKey(idempotencyKey);
        preflightOrgScopedBatch(context.organizationId(), importId);

        var requestHash = ImportCommandIdempotency.requestHash(
                ImportCommandIdempotency.OPERATION_CONFIRM, context.organizationId(),
                context.organizationMemberId(), importId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var decision = idempotency.reserve(context.organizationId(), context.organizationMemberId(),
                    ImportCommandIdempotency.OPERATION_CONFIRM, idempotencyKey, requestHash);
            if (decision.replay()) {
                return responseSerializer.importDetailFromJson(decision.responseBody());
            }

            // Semantic re-confirm of already committed truth remains a no-op
            // replay and therefore does not need a new Close admission gate.
            var preRead = batchMapper.findByIdAndOrganization(importId, context.organizationId());
            var preLatest = attemptMapper.findLatestByBatch(importId);
            if (isSemanticReconfirm(preRead, preLatest)) {
                var replayed = currentDetail(context.organizationId(), importId);
                idempotency.finalize(decision.id(), 200,
                        responseSerializer.importDetailJson(replayed));
                return replayed;
            }

            if (preRead == null || preRead.organizationId() != context.organizationId()) {
                throw notFound();
            }
            // Validate the immutable pre-admission shape before taking any
            // workflow row lock. The actual mutation is revalidated after the
            // organization + period locks below.
            requireConfirmable(preRead, preLatest);
            closeAdmission.lockAndRequireOpenPeriodsForAttempt(context.organizationId(), preLatest.id());

            var batch = batchMapper.findByIdForUpdate(importId);
            if (batch == null || batch.organizationId() != context.organizationId()) {
                throw notFound();
            }
            var latest = attemptMapper.findLatestByBatch(importId);
            if (latest == null || latest.importBatchId() != batch.id()) {
                throw stateConflict("Import cannot be confirmed",
                        "The import has no latest attempt matching the batch.");
            }
            if (isSemanticReconfirm(batch, latest)) {
                var replayed = currentDetail(context.organizationId(), importId);
                idempotency.finalize(decision.id(), 200,
                        responseSerializer.importDetailJson(replayed));
                return replayed;
            }
            if (batch.status() == ImportBatchStatus.CONFIRMED) {
                throw stateConflict("Import cannot be confirmed",
                        "A confirmed import can only re-confirm its confirmed attempt.");
            }
            requireConfirmable(batch, latest);

            var now = clock.instant();
            if (batchMapper.markConfirmed(batch.id(), latest.id(), now) != 1) {
                throw new IllegalStateException("Import confirmation must update exactly one row");
            }
            audit.importConfirmed(context.organizationId(), context.userId(), importId,
                    latest.id(), batch.status().name());
            // Idempotent replays do not re-increment: only a genuinely new
            // confirmation of a fresh attempt counts as a completed import.
            metrics.importCompleted(batch.expectedProviderCode(), "SUCCEEDED");

            var detail = currentDetail(context.organizationId(), importId);
            idempotency.finalize(decision.id(), 200, responseSerializer.importDetailJson(detail));
            return detail;
        }));
    }

    private static boolean isSemanticReconfirm(ImportBatch batch, ImportAttempt latest) {
        return batch != null
                && batch.status() == ImportBatchStatus.CONFIRMED
                && latest != null
                && batch.confirmedAttemptId() != null
                && batch.confirmedAttemptId() == latest.id();
    }

    private void requireConfirmable(ImportBatch batch, ImportAttempt latest) {
        if (batch.status() != ImportBatchStatus.READY_FOR_REVIEW
                || latest == null
                || latest.status() != ImportAttemptStatus.SUCCEEDED
                || latest.errorCount() != 0) {
            throw stateConflict("Import cannot be confirmed",
                    "Only a READY_FOR_REVIEW import with a SUCCEEDED attempt and no blocking errors can be confirmed.");
        }
    }

    private void requireCancelable(ImportBatch batch, ImportAttempt latest) {
        var pendingQueued = batch.status() == ImportBatchStatus.PENDING
                && latest != null && latest.status() == ImportAttemptStatus.QUEUED;
        var processingRunning = batch.status() == ImportBatchStatus.PROCESSING
                && latest != null && latest.status() == ImportAttemptStatus.RUNNING;
        var failed = batch.status() == ImportBatchStatus.FAILED
                && latest != null && latest.status() == ImportAttemptStatus.FAILED;
        if (!pendingQueued && !processingRunning && !failed) {
            throw stateConflict("Import cannot be canceled",
                    "Only a PENDING import with a queued attempt, a PROCESSING import with a running attempt, or a FAILED import with a failed attempt can be canceled.");
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
}
