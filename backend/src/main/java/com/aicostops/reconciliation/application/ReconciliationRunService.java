package com.aicostops.reconciliation.application;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public final class ReconciliationRunService {

    private static final String PERMISSION_RUN = "RECONCILIATION_RUN";
    private static final String FAILURE_CODE = "RECONCILIATION_EVALUATION_FAILED";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodFinancialWriteFence periodFence;
    private final ReconciliationExternalTruthPort externalTruth;
    private final ReconciliationInternalTruthPort internalTruth;
    private final ReconciliationTolerancePolicy tolerancePolicy;
    private final ReconciliationMatchEngine matchEngine;
    private final ReconciliationTruthHasher hasher;
    private final ReconciliationMapper mapper;
    private final ReconciliationAuditPort audit;
    private final ObjectMapper objectMapper;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final TransactionTemplate snapshotTransactions;
    private final Clock clock;

    public ReconciliationRunService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodFinancialWriteFence periodFence,
            ReconciliationExternalTruthPort externalTruth,
            ReconciliationInternalTruthPort internalTruth,
            ReconciliationTolerancePolicy tolerancePolicy,
            ReconciliationMatchEngine matchEngine,
            ReconciliationTruthHasher hasher,
            ReconciliationMapper mapper,
            ReconciliationAuditPort audit,
            ObjectMapper objectMapper,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.periodFence = periodFence;
        this.externalTruth = externalTruth;
        this.internalTruth = internalTruth;
        this.tolerancePolicy = tolerancePolicy;
        this.matchEngine = matchEngine;
        this.hasher = hasher;
        this.mapper = mapper;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.snapshotTransactions = new TransactionTemplate(transactionManager);
        this.snapshotTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTransactions.setReadOnly(true);
        this.clock = clock;
    }

    public com.aicostops.reconciliation.domain.ReconciliationRun run(
            AuthenticatedUser user, long billingPeriodId) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_RUN);
        var tolerance = tolerancePolicy.amount();
        var now = clock.instant();

        var started = transactions.execute(status -> {
            var period = periodFence.lockOpenById(context.organizationId(), billingPeriodId);
            mapper.insertRun(context.organizationId(), period.id(), "RUNNING",
                    ReconciliationAlgorithm.VERSION, tolerance, "{}",
                    context.organizationMemberId(), now, now, now);
            var runId = mapper.lastInsertId();
            return new StartedRun(runId, period.periodStart(), period.periodEnd());
        });
        if (started == null) {
            throw new IllegalStateException("Reconciliation start transaction returned no result");
        }

        try {
            var snapshot = snapshotTransactions.execute(status -> {
                var external = externalTruth.aggregateConfirmedCharges(
                        context.organizationId(), started.periodStart(), started.periodEnd());
                var internal = internalTruth.aggregateProviderLedger(
                        context.organizationId(), billingPeriodId);
                var summary = matchEngine.match(external, internal, tolerance);
                return new Snapshot(summary, hasher.hash(summary.rows()));
            });
            if (snapshot == null) {
                throw new IllegalStateException("Reconciliation snapshot transaction returned no result");
            }

            var finished = clock.instant();
            var completed = transactions.execute(status -> {
                var locked = mapper.selectRunByIdForUpdate(context.organizationId(), started.runId());
                if (locked == null || locked.status()
                        != com.aicostops.reconciliation.domain.ReconciliationRunStatus.RUNNING) {
                    throw new IllegalStateException("Reconciliation run is no longer RUNNING");
                }
                for (var row : snapshot.summary().rows()) {
                    if (row.caseType() != null) {
                        mapper.insertCase(context.organizationId(), started.runId(),
                                row.providerAccountId(), row.currency(), row.caseType().name(),
                                row.externalAmount(), row.internalAmount(), row.difference(),
                                row.externalRowCount(), row.internalRowCount(), finished, finished);
                    }
                }
                var summaryJson = objectMapper.writeValueAsString(Map.of(
                        "totalKeys", snapshot.summary().rows().size(),
                        "matchedCount", snapshot.summary().matchedCount(),
                        "discrepancyCount", snapshot.summary().discrepancyCount()));
                if (mapper.markRunCompleted(context.organizationId(), started.runId(),
                        snapshot.basisHash(), summaryJson, finished, finished) != 1) {
                    throw new IllegalStateException("Reconciliation run completion CAS failed");
                }
                audit.runCompleted(context.organizationId(), context.userId(), started.runId(),
                        billingPeriodId, snapshot.summary().discrepancyCount(),
                        ReconciliationAlgorithm.VERSION);
                return mapper.selectRunByIdAndOrganization(context.organizationId(), started.runId());
            });
            if (completed == null) {
                throw new IllegalStateException("Reconciliation finalize transaction returned no result");
            }
            metrics.reconciliationRun("COMPLETED");
            return completed;
        } catch (RuntimeException failure) {
            metrics.reconciliationRun("FAILED");
            failRun(context.organizationId(), context.userId(), billingPeriodId, started.runId());
            throw failure;
        }
    }

    private void failRun(long organizationId, long actorUserId, long billingPeriodId, long runId) {
        try {
            var failedAt = clock.instant();
            transactions.executeWithoutResult(status -> {
                var locked = mapper.selectRunByIdForUpdate(organizationId, runId);
                if (locked == null
                        || locked.status() != com.aicostops.reconciliation.domain.ReconciliationRunStatus.RUNNING) {
                    return;
                }
                if (mapper.markRunFailed(organizationId, runId, FAILURE_CODE,
                        "Reconciliation evaluation failed.", failedAt, failedAt) != 1) {
                    throw new IllegalStateException("Reconciliation failure CAS failed");
                }
                audit.runFailed(organizationId, actorUserId, runId, billingPeriodId, FAILURE_CODE);
            });
        } catch (RuntimeException ignored) {
            // Preserve the original evaluation/finalization exception. The RUNNING
            // row remains recoverable evidence if even failure finalization cannot commit.
        }
    }

    private record StartedRun(long runId, java.time.Instant periodStart, java.time.Instant periodEnd) {
    }

    private record Snapshot(ReconciliationReadModels.MatchSummary summary, String basisHash) {
    }
}
