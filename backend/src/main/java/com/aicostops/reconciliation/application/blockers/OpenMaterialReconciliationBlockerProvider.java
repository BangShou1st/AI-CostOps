package com.aicostops.reconciliation.application.blockers;

import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.application.ReconciliationAlgorithm;
import com.aicostops.reconciliation.application.ReconciliationMatchEngine;
import com.aicostops.reconciliation.application.ReconciliationTolerancePolicy;
import com.aicostops.reconciliation.application.ReconciliationTruthHasher;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class OpenMaterialReconciliationBlockerProvider implements CloseBlockerProvider {

    private final ReconciliationMapper mapper;
    private final ReconciliationExternalTruthPort externalTruth;
    private final ReconciliationInternalTruthPort internalTruth;
    private final ReconciliationTolerancePolicy tolerance;
    private final ReconciliationMatchEngine engine;
    private final ReconciliationTruthHasher hasher;

    public OpenMaterialReconciliationBlockerProvider(
            ReconciliationMapper mapper,
            ReconciliationExternalTruthPort externalTruth,
            ReconciliationInternalTruthPort internalTruth,
            ReconciliationTolerancePolicy tolerance,
            ReconciliationMatchEngine engine,
            ReconciliationTruthHasher hasher) {
        this.mapper = mapper;
        this.externalTruth = externalTruth;
        this.internalTruth = internalTruth;
        this.tolerance = tolerance;
        this.engine = engine;
        this.hasher = hasher;
    }

    @Override public CloseBlockerCode code() { return CloseBlockerCode.OPEN_MATERIAL_RECONCILIATION; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var latest = mapper.selectLatestRunForPeriod(
                context.organizationId(), context.billingPeriodId());
        if (latest == null) {
            return stale("NO_RECONCILIATION_RUN", null);
        }
        if (latest.status() != ReconciliationRunStatus.COMPLETED) {
            return stale("LATEST_RUN_NOT_COMPLETED", latest.id());
        }
        if (!ReconciliationAlgorithm.VERSION.equals(latest.algorithmVersion())) {
            return stale("ALGORITHM_VERSION_CHANGED", latest.id());
        }
        if (latest.toleranceAmount().compareTo(tolerance.amount()) != 0) {
            return stale("TOLERANCE_CHANGED", latest.id());
        }
        var current = engine.match(
                externalTruth.aggregateConfirmedCharges(context.organizationId(),
                        context.periodStart(), context.periodEnd()),
                internalTruth.aggregateProviderLedger(context.organizationId(),
                        context.billingPeriodId()),
                tolerance.amount());
        var currentHash = hasher.hash(current.rows());
        if (!currentHash.equals(latest.basisHash())) {
            return CloseBlockerResult.fail(code(), 1,
                    Map.of("reason", "FINANCIAL_BASIS_CHANGED",
                            "latestRunId", Long.toString(latest.id())));
        }
        var unresolved = mapper.countUnresolvedCases(context.organizationId(), latest.id());
        if (unresolved > 0) {
            return CloseBlockerResult.fail(code(), unresolved,
                    Map.of("reason", "UNRESOLVED_CASES",
                            "latestRunId", Long.toString(latest.id()),
                            "unresolvedCaseCount", unresolved));
        }
        return CloseBlockerResult.pass(code(),
                Map.of("latestRunId", Long.toString(latest.id()),
                        "basisHash", currentHash));
    }

    private CloseBlockerResult stale(String reason, Long runId) {
        var summary = runId == null
                ? Map.<String, Object>of("reason", reason)
                : Map.<String, Object>of("reason", reason, "latestRunId", Long.toString(runId));
        return CloseBlockerResult.fail(code(), 1, summary);
    }
}
