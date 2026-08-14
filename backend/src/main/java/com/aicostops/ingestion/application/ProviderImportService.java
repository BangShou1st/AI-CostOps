package com.aicostops.ingestion.application;

import com.aicostops.evidence.application.EvidenceStorageService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.ingestion.domain.ImportAttempt;
import com.aicostops.ingestion.domain.ImportBatch;
import com.aicostops.ingestion.domain.ImportSourceType;
import com.aicostops.ingestion.infrastructure.ImportAttemptMapper;
import com.aicostops.ingestion.infrastructure.ImportBatchMapper;
import com.aicostops.organization.application.ProviderAccountDirectory;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.io.InputStream;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent provider-import creation.
 *
 * <p>Request order: authorization -> ACTIVE provider account -> registered adapter /
 * parser version -> store/reuse Evidence -> create or reuse ImportBatch. A reused
 * Batch returns its latest Attempt and never implicitly creates a new one.
 */
@Service
public class ProviderImportService {

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ProviderAccountDirectory providerAccounts;
    private final ProviderAdapterRegistry registry;
    private final EvidenceStorageService evidenceStorage;
    private final ImportBatchMapper batchMapper;
    private final ImportAttemptMapper attemptMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProviderImportService(
            AuthorizationContextService authorizationContexts,
            ProviderAccountDirectory providerAccounts,
            ProviderAdapterRegistry registry,
            EvidenceStorageService evidenceStorage,
            ImportBatchMapper batchMapper,
            ImportAttemptMapper attemptMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.providerAccounts = providerAccounts;
        this.registry = registry;
        this.evidenceStorage = evidenceStorage;
        this.batchMapper = batchMapper;
        this.attemptMapper = attemptMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ProviderImportResult create(
            AuthenticatedUser authenticatedUser,
            String originalFilename,
            String mediaType,
            InputStream content,
            long providerAccountId,
            ImportSourceType sourceType) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "EVIDENCE_UPLOAD_PROVIDER");
        var account = providerAccounts.findActive(context.organizationId(), providerAccountId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                        "Provider account not found",
                        "The provider account is not available in the current organization."));
        var adapter = registry.findByCode(account.providerCode())
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                        "Unsupported provider",
                        "No provider adapter is registered for provider code " + account.providerCode() + "."));

        var stored = evidenceStorage.store(
                context.organizationId(), context.organizationMemberId(), originalFilename, mediaType, content);

        var outcome = transactions.execute(status -> createOrReuseBatch(
                context.organizationId(), context.organizationMemberId(), stored.evidence().id(),
                providerAccountId, account.providerCode(), sourceType, adapter.parserVersion()));
        if (outcome == null) {
            throw new IllegalStateException("Batch creation transaction must produce a result");
        }
        return new ProviderImportResult(
                stored.evidence().id(), outcome.batch().id(), outcome.attempt().id(),
                outcome.batch().status().name(), stored.duplicate(), outcome.duplicateBatch());
    }

    private BatchOutcome createOrReuseBatch(
            long organizationId,
            long createdByMemberId,
            long evidenceId,
            long providerAccountId,
            String expectedProviderCode,
            ImportSourceType sourceType,
            String parserVersion) {
        var existing = batchMapper.findByIdentity(
                evidenceId, providerAccountId, sourceType.name(), parserVersion);
        if (existing != null) {
            return reuse(existing);
        }
        var now = clock.instant();
        try {
            batchMapper.insert(organizationId, evidenceId, providerAccountId, expectedProviderCode,
                    sourceType.name(), parserVersion, createdByMemberId, now);
        } catch (DuplicateKeyException concurrentIdentity) {
            // Concurrent same-identity creation converges on the winner's Batch.
            // The winner's Initial Attempt must be read with a locking current read:
            // a consistent read would reuse this transaction's old REPEATABLE READ
            // snapshot and could miss the just-committed Attempt.
            var winner = batchMapper.findByIdentityForUpdate(
                    evidenceId, providerAccountId, sourceType.name(), parserVersion);
            if (winner != null) {
                return reuseAfterConcurrentWinner(winner);
            }
            throw concurrentIdentity;
        }
        var batchId = batchMapper.lastInsertId();
        attemptMapper.insertQueued(batchId, 1, "INITIAL", null, parserVersion, now, now);
        var batch = batchMapper.findByIdAndOrganization(batchId, organizationId);
        var attempt = attemptMapper.findById(attemptMapper.lastInsertId());
        if (batch == null || attempt == null) {
            throw new IllegalStateException("Created ImportBatch/Attempt must be readable");
        }
        return new BatchOutcome(batch, attempt, false);
    }

    private BatchOutcome reuse(ImportBatch existing) {
        var latest = attemptMapper.findLatestByBatch(existing.id());
        if (latest == null) {
            throw new IllegalStateException("An existing ImportBatch must have at least one Attempt");
        }
        return new BatchOutcome(existing, latest, true);
    }

    private BatchOutcome reuseAfterConcurrentWinner(ImportBatch winner) {
        var latest = attemptMapper.findLatestByBatchForUpdate(winner.id());
        if (latest == null) {
            throw new IllegalStateException("A concurrently created ImportBatch must have at least one Attempt");
        }
        return new BatchOutcome(winner, latest, true);
    }

    public record ProviderImportResult(
            long evidenceId,
            long importBatchId,
            long latestAttemptId,
            String batchStatus,
            boolean duplicateEvidence,
            boolean duplicateBatch) {
    }

    private record BatchOutcome(ImportBatch batch, ImportAttempt attempt, boolean duplicateBatch) {
    }
}
