package com.aicostops.ingestion.application;

import com.aicostops.cost.application.CanonicalCostWritePort;
import com.aicostops.cost.application.CanonicalizationInput;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.ingestion.infrastructure.ImportAttemptMapper;
import com.aicostops.ingestion.infrastructure.ImportIssueMapper;
import com.aicostops.ingestion.infrastructure.ImportWorkerProperties;
import com.aicostops.ingestion.infrastructure.RawProviderRecordMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lease-fenced, bounded RawProviderRecord/ImportIssue persistence.
 *
 * <p>Each call is one short transaction: it first locks and verifies Attempt
 * ownership {@code (attemptId, workerId, leaseVersion, unexpired lease)} inside the
 * transaction, then inserts the bounded record batch and updates Attempt counters.
 * A stale worker gets zero inserts and is told it lost the lease.
 *
 * <p>Canonical facts for each record are written through {@link CanonicalCostWritePort}
 * in the same transaction (after the raw row exists, before counters), so a
 * canonicalization failure rolls the whole bounded batch back. Records with
 * {@code normalize_status='ERROR'} or a null normalized payload never reach the
 * canonical writer.
 */
@Service
public class ImportRawPersistenceService {

    private final ImportAttemptMapper attemptMapper;
    private final RawProviderRecordMapper rawMapper;
    private final ImportIssueMapper issueMapper;
    private final CanonicalCostWritePort canonicalCostWritePort;
    private final ImportWorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ImportRawPersistenceService(
            ImportAttemptMapper attemptMapper,
            RawProviderRecordMapper rawMapper,
            ImportIssueMapper issueMapper,
            CanonicalCostWritePort canonicalCostWritePort,
            ImportWorkerProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.attemptMapper = attemptMapper;
        this.rawMapper = rawMapper;
        this.issueMapper = issueMapper;
        this.canonicalCostWritePort = canonicalCostWritePort;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public PersistResult persist(ImportLeaseService.ImportLease lease, List<NormalizedProviderRecord> records,
            long orgId, String providerCode) {
        if (records.isEmpty()) {
            return new PersistResult(0, 0, false);
        }
        return transactions.execute(status -> {
            var owned = attemptMapper.lockAndVerifyOwnership(
                    lease.attemptId(), lease.leaseOwner(), lease.leaseVersion());
            if (owned != 1) {
                return new PersistResult(0, 0, true);
            }
            var now = clock.instant();
            var warnings = 0L;
            var errors = 0L;
            var valid = 0L;
            var rawRows = new ArrayList<RawProviderRecordMapper.RawRecordInsert>(records.size());
            var normalizedPayloads = new HashMap<Integer, JsonNode>(records.size());
            for (var record : records) {
                var rawPayload = serialize(PayloadRedactor.redact(record.rawPayload()));
                // The sanitized normalized JSON is produced exactly once and is the
                // single source for both the persisted normalized_payload and the
                // canonicalization input (invariant: one sanitized normalized JSON).
                var redactedNormalized = record.normalizedPayload() == null
                        ? null : PayloadRedactor.redactNormalizedPayload(record.normalizedPayload());
                var normalizedPayload = redactedNormalized == null ? null : serialize(redactedNormalized);
                normalizedPayloads.put(record.index(),
                        redactedNormalized == null ? null : objectMapper.valueToTree(redactedNormalized));
                // User-controlled metadata (adapter-derived locators/keys such as
                // GLM worksheet names) passes the same secret-shaped redaction as
                // payloads, bounded to the VARCHAR(500) column limits.
                rawRows.add(new RawProviderRecordMapper.RawRecordInsert(
                        lease.attemptId(), record.index(),
                        IssueSanitizer.sanitizeLocator(record.locator()),
                        IssueSanitizer.sanitizeRecordKey(record.providerRecordKey()),
                        rawPayload, normalizedPayload,
                        record.usageStart(), record.usageEnd(), record.normalizeStatus().name(), now));
            }
            if (rawMapper.insertBatch(rawRows) != rawRows.size()) {
                throw new IllegalStateException("Raw provider batch insert affected an unexpected row count");
            }
            var firstIndex = records.getFirst().index();
            var lastIndex = records.getLast().index();
            var rawIds = new HashMap<Long, Long>(records.size());
            for (var row : rawMapper.findIdsByIndexRange(lease.attemptId(), firstIndex, lastIndex)) {
                rawIds.put(row.recordIndex(), row.id());
            }
            if (rawIds.size() != records.size()) {
                throw new IllegalStateException("Raw provider batch did not produce one id per record");
            }

            var issueRows = new ArrayList<ImportIssueMapper.IssueInsert>();
            var canonicalInputs = new ArrayList<CanonicalizationInput>();
            for (var record : records) {
                var rawId = rawIds.get((long) record.index());
                if (rawId == null) {
                    throw new IllegalStateException("Raw provider record id is missing for index "
                            + record.index());
                }
                for (var issue : record.issues()) {
                    issueRows.add(new ImportIssueMapper.IssueInsert(
                            lease.attemptId(), rawId, issue.severity().name(), issue.issueCode(),
                            IssueSanitizer.sanitizeLocator(issue.recordLocator()),
                            IssueSanitizer.sanitizeFieldName(issue.fieldName()),
                            IssueSanitizer.sanitizeMessage(issue.message()),
                            IssueSanitizer.sanitizeMasked(issue.rawValueMasked()), now));
                    if (issue.severity().name().equals("ERROR")) {
                        errors++;
                    } else {
                        warnings++;
                    }
                }
                if (record.normalizeStatus() == RawRecordNormalizeStatus.NORMALIZED) {
                    valid++;
                }
                // Canonicalization consumes the same sanitized JSON inside the same
                // bounded transaction; ERROR/null records write nothing. The
                // whitelisted mapping and the masked-credential guard keep secrets
                // out of canonical tables.
                if (record.normalizeStatus() != RawRecordNormalizeStatus.ERROR
                        && record.normalizedPayload() != null) {
                    canonicalInputs.add(new CanonicalizationInput(
                            orgId, providerCode, lease.attemptId(), rawId,
                            record.index(), record.locator(), normalizedPayloads.get(record.index()),
                            record.usageStart(), record.usageEnd()));
                }
            }
            if (!issueRows.isEmpty() && issueMapper.insertBatch(issueRows) != issueRows.size()) {
                throw new IllegalStateException("Import issue batch insert affected an unexpected row count");
            }
            canonicalCostWritePort.writeAll(canonicalInputs);
            attemptMapper.incrementCounters(lease.attemptId(), records.size(), valid, warnings, errors);
            return new PersistResult(records.size(), issueRows.size(), false);
        });
    }

    /**
     * Persists adapter inspection issues attached to the Attempt (no raw record).
     * Also fenced; a stale worker gets zero inserts.
     */
    public PersistResult persistInspectionIssues(
            ImportLeaseService.ImportLease lease, List<ImportIssueDraft> issues) {
        if (issues.isEmpty()) {
            return new PersistResult(0, 0, false);
        }
        return transactions.execute(status -> {
            var owned = attemptMapper.lockAndVerifyOwnership(
                    lease.attemptId(), lease.leaseOwner(), lease.leaseVersion());
            if (owned != 1) {
                return new PersistResult(0, 0, true);
            }
            var now = clock.instant();
            var warnings = 0L;
            var errors = 0L;
            var issueRows = new ArrayList<ImportIssueMapper.IssueInsert>(issues.size());
            for (var issue : issues) {
                issueRows.add(new ImportIssueMapper.IssueInsert(
                        lease.attemptId(), null, issue.severity().name(), issue.issueCode(),
                        IssueSanitizer.sanitizeLocator(issue.recordLocator()),
                        IssueSanitizer.sanitizeFieldName(issue.fieldName()),
                        IssueSanitizer.sanitizeMessage(issue.message()),
                        IssueSanitizer.sanitizeMasked(issue.rawValueMasked()), now));
                if (issue.severity().name().equals("ERROR")) {
                    errors++;
                } else {
                    warnings++;
                }
            }
            if (issueMapper.insertBatch(issueRows) != issueRows.size()) {
                throw new IllegalStateException("Inspection issue batch insert affected an unexpected row count");
            }
            attemptMapper.incrementCounters(lease.attemptId(), 0, 0, warnings, errors);
            return new PersistResult(0, issues.size(), false);
        });
    }

    public int persistenceBatchSize() {
        return properties.persistenceBatchSize();
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize provider payload", exception);
        }
    }

    /** Outcome of one bounded persistence transaction. */
    public record PersistResult(long recordsPersisted, long issuesPersisted, boolean leaseLost) {
    }
}
