package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.application.ProviderCorrelationProfileRegistry.CorrelationField;
import com.aicostops.reconciliation.application.ReconciliationReadModels.MatchRow;
import com.aicostops.reconciliation.domain.ReconciliationDifferenceKind;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ExactCorrelationCandidate;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ReconciliationEvidenceRow;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.UnresolvedGatewayRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                    "AGGREGATE_SCOPE", classifyAggregateDifference(row),
                    null, null, null, null, null, null, null, null, null, null, null,
                    row.externalAmount(), row.internalAmount(), row.difference(),
                    now));
        }

        for (var group : exactCorrelationGroups(organizationId, periodStart, periodEnd)) {
            var candidate = group.getFirst();
            evidence.add(new ReconciliationEvidenceRow(
                    organizationId, runId, null,
                    "EXACT:CHARGE:" + candidate.chargeFactId() + ":REQUEST:" + candidate.requestId(),
                    candidate.providerAccountId(), candidate.currency(), "EXACT_PROVIDER_REQUEST",
                    null, candidate.chargeFactId(), candidate.requestId(),
                    candidate.routeAttemptId(),
                    null, null, null, null, null, null, candidate.providerRequestId(),
                    null, null, null, null, now));
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
                    null, null, null, now));
        }
        return List.copyOf(evidence);
    }

    /**
     * Fail-closed classification for an aggregate scope difference. Only a
     * certified duplicate review state proves DUPLICATE_EXTERNAL_CHARGE; every
     * other aggregate difference remains UNCLASSIFIED until reviewed evidence
     * exists. Unsupported timing/pricing proofs are never guessed.
     */
    private String classifyAggregateDifference(MatchRow row) {
        if (row.difference() == null || row.difference().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return ReconciliationDifferenceKind.UNCLASSIFIED.name();
    }

    /**
     * Builds the exact correlation groups from the eligible candidate pairs:
     * first only charges whose Provider + durable source schema certifies the
     * key as a provider request id (an uncertified schema with a coincidentally
     * equal key has no standing and must not poison the uniqueness count),
     * then grouping by the frozen correlation identity — binary-exact provider
     * request id + provider account + currency. A group is an exact
     * correlation only when it holds exactly one distinct Charge and exactly
     * one distinct request/current attempt; duplicate certified Charges or
     * duplicate Gateway candidates stay ambiguous and fail closed.
     */
    private List<List<ExactCorrelationCandidate>> exactCorrelationGroups(long organizationId,
            Instant periodStart, Instant periodEnd) {
        var groups = new LinkedHashMap<String, List<ExactCorrelationCandidate>>();
        for (var candidate : mapper.selectExactCorrelationCandidates(organizationId,
                periodStart, periodEnd)) {
            if (correlationProfiles.providerRecordKeySemantics(candidate.providerCode(),
                    candidate.sourceType(), candidate.parserVersion())
                    != CorrelationField.PROVIDER_REQUEST_ID) {
                continue;
            }
            groups.computeIfAbsent(candidate.providerRequestId() + "\u0000"
                            + candidate.providerAccountId() + "\u0000" + candidate.currency(),
                    key -> new ArrayList<>()).add(candidate);
        }
        var exact = new ArrayList<List<ExactCorrelationCandidate>>();
        for (var group : groups.values()) {
            var distinctCharges = group.stream().map(ExactCorrelationCandidate::chargeFactId)
                    .distinct().count();
            var distinctRequests = group.stream()
                    .map(candidate -> candidate.requestId() + ":" + candidate.routeAttemptId())
                    .distinct().count();
            if (distinctCharges == 1 && distinctRequests == 1) {
                exact.add(group);
            }
        }
        return exact;
    }
}
