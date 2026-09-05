package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.application.ProviderCorrelationProfileRegistry.CorrelationField;
import com.aicostops.reconciliation.application.ReconciliationReadModels.MatchRow;
import com.aicostops.reconciliation.domain.ReconciliationDifferenceKind;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ExactCorrelationGroup;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ReconciliationEvidenceRow;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.UnresolvedGatewayRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deterministic hybrid evidence generation inside the read-only reconciliation
 * snapshot. Evidence is bounded lineage only: no prompt, completion, reasoning
 * or raw Provider body is ever represented here. Difference classification is
 * evidence-gated; without stored proof the mandatory label is UNCLASSIFIED.
 */
@Service
public class HybridReconciliationEvidenceService {

    private final HybridReconciliationMapper mapper;
    private final ProviderCorrelationProfileRegistry correlationProfiles;

    public HybridReconciliationEvidenceService(
            HybridReconciliationMapper mapper,
            ProviderCorrelationProfileRegistry correlationProfiles) {
        this.mapper = mapper;
        this.correlationProfiles = correlationProfiles;
    }

    public List<ReconciliationEvidenceRow> buildEvidence(
            long organizationId, long runId, long billingPeriodId,
            Instant periodStart, Instant periodEnd,
            List<MatchRow> summaryRows, Instant now) {
        var evidence = new ArrayList<ReconciliationEvidenceRow>();

        for (var row : summaryRows) {
            if (row.caseType() == null) {
                continue;
            }
            evidence.add(new ReconciliationEvidenceRow(
                    organizationId, runId, null,
                    "AGGREGATE:" + row.providerAccountId() + ":" + row.currency(),
                    row.providerAccountId(), row.currency(),
                    "AGGREGATE_SCOPE", classifyAggregateDifference(organizationId, row,
                            periodStart, periodEnd),
                    null, null, null, null, null, null, null, null, null, null,
                    row.externalAmount(), row.internalAmount(), row.difference(),
                    now));
        }

        for (var group : mapper.selectExactCorrelationGroups(organizationId,
                periodStart, periodEnd)) {
            if (group.chargeCount() != 1 || group.requestCount() != 1) {
                // Ambiguous duplicate Provider request ids never auto-bind.
                continue;
            }
            if (correlationProfiles.providerRecordKeySemantics(
                    mapper.selectChargeProviderCode(organizationId, group.chargeFactId()))
                    != CorrelationField.PROVIDER_REQUEST_ID) {
                // The import profile does not certify the key as a request id.
                continue;
            }
            evidence.add(new ReconciliationEvidenceRow(
                    organizationId, runId, null,
                    "EXACT:CHARGE:" + group.chargeFactId() + ":REQUEST:" + group.requestId(),
                    group.providerAccountId(), group.currency(), "EXACT_PROVIDER_REQUEST", null,
                    group.chargeFactId(), group.requestId(), group.routeAttemptId(),
                    null, null, null, null, null, null, group.providerRequestId(),
                    null, null, null, now));
        }

        for (var unresolved : mapper.selectUnresolvedGatewayRequests(organizationId,
                billingPeriodId)) {
            evidence.add(new ReconciliationEvidenceRow(
                    organizationId, runId, null,
                    "GATEWAY_UNRESOLVED:REQUEST:" + unresolved.requestId(),
                    unresolved.providerAccountId(), unresolved.currency(),
                    "GATEWAY_UNRESOLVED", null,
                    null, unresolved.requestId(), unresolved.routeAttemptId(),
                    unresolved.usageFactId(), unresolved.settlementId(), null,
                    null, null, null, null, null,
                    null, null, now));
        }
        return List.copyOf(evidence);
    }

    /**
     * Fail-closed classification for an aggregate scope difference. Only a
     * certified duplicate review state proves DUPLICATE_EXTERNAL_CHARGE; every
     * other aggregate difference remains UNCLASSIFIED until reviewed evidence
     * exists. Unsupported timing/pricing proofs are never guessed.
     */
    private String classifyAggregateDifference(long organizationId, MatchRow row,
            Instant periodStart, Instant periodEnd) {
        if (row.difference() == null || row.difference().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return ReconciliationDifferenceKind.UNCLASSIFIED.name();
    }
}
