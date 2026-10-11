package com.aicostops.advisor.application;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes the frozen evidence contract consumed by the Gateway worker.
 *
 * <p>Amounts retain decimal scale as strings; driver references follow snapshot
 * order. Persistence and transaction ownership remain in AdvisorJobCommandService.
 */
@Component
public class AdvisorEvidenceSnapshotSerializer {

    private final ObjectMapper objectMapper;

    public AdvisorEvidenceSnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String referencesJson(AdvisorEvidence.Envelope envelope) {
        // Fact references cover money facts plus deterministic driver references so the model can
        // cite drivers without failing output validation; all IDs are server-generated.
        var allRefs = new java.util.ArrayList<>(envelope.factReferenceIds());
        allRefs.addAll(driverReferenceIds(envelope));
        final String refsJson;
        try {
            refsJson = objectMapper.writeValueAsString(allRefs);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence references are unavailable", ex);
        }
        return refsJson;
    }

    /** Deterministic driver reference ids, aligned with the snapshot driver order. */
    static List<String> driverReferenceIds(String subjectType, long subjectId, int driverCount) {
        var refs = new java.util.ArrayList<String>();
        for (var i = 0; i < driverCount; i++) {
            refs.add(subjectType.toLowerCase(java.util.Locale.ROOT) + ":" + subjectId + ":driver:" + i);
        }
        return refs;
    }

    private List<String> driverReferenceIds(AdvisorEvidence.Envelope envelope) {
        return driverReferenceIds(envelope.subjectType(), envelope.subjectId(),
                envelope.drivers().size());
    }

    /**
     * Snapshot facts with amounts as plain decimal strings (never JSON numbers): JSON number
     * spellings do not preserve BigDecimal scale ({@code 12.50} may read back as {@code 12.5}),
     * which would break fingerprint recomputation on the Gateway side.
     */
    String factsJson(AdvisorEvidence.Envelope envelope) {
        try {
            var out = new java.util.ArrayList<java.util.Map<String, String>>();
            for (var fact : envelope.facts()) {
                out.add(java.util.Map.of("factId", fact.factId(), "label", fact.label(),
                        "amount", fact.amount().toPlainString(), "currency", fact.currency()));
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence snapshot is unavailable", ex);
        }
    }

    String driversJson(AdvisorEvidence.Envelope envelope) {
        try {
            var refs = driverReferenceIds(envelope);
            var out = new java.util.ArrayList<java.util.Map<String, String>>();
            for (var i = 0; i < envelope.drivers().size(); i++) {
                var driver = envelope.drivers().get(i);
                out.add(java.util.Map.of("id", refs.get(i), "dimension", driver.dimension(),
                        "key", driver.key(), "deltaAmount", driver.deltaAmount().toPlainString(),
                        "currency", driver.currency()));
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Advisor evidence snapshot is unavailable", ex);
        }
    }

    private java.util.Map<String, String> snapshotSummary(AdvisorEvidence.Envelope envelope) {
        return java.util.Map.of("forecastSummary", envelope.forecastSummary(),
                "budgetRiskSummary", envelope.budgetRiskSummary(),
                "savingsSummary", envelope.savingsSummary());
    }

    String summaryJson(AdvisorEvidence.Envelope envelope) {
        return objectMapper.writeValueAsString(snapshotSummary(envelope));
    }
}
