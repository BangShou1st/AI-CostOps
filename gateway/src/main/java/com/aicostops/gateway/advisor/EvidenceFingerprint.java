package com.aicostops.gateway.advisor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Gateway-side mirror of the backend {@code AdvisorEvidence} fingerprint (M18 round-3).
 *
 * <p>The worker recomputes this canonical hash from the persisted immutable evidence snapshot
 * before any Provider I/O; a mismatch fails the job with {@code EVIDENCE_INTEGRITY_FAILED} so
 * facts actually sent to the model can never differ from the recorded evidence fingerprint.
 * Amounts are normalized through {@link BigDecimal#toPlainString} so JSON number spellings can
 * never drift from the backend canonical form. The cross-module contract test pins the exact
 * canonical string and hash against a shared frozen vector.
 */
final class EvidenceFingerprint {

    private EvidenceFingerprint() {
    }

    record Fact(String id, String amountPlain) {
    }

    record Driver(String dimension, String key, String deltaPlain) {
    }

    static String fingerprint(String subjectType, long subjectId, String currency, int schemaVersion,
            List<Fact> facts, List<Driver> drivers, String forecastSummary, String budgetRiskSummary,
            String savingsSummary) {
        try {
            var canonical = new StringBuilder(subjectType).append('|').append(subjectId).append('|')
                    .append(currency).append('|').append(schemaVersion).append('|');
            for (var fact : facts) {
                canonical.append(fact.id()).append('=').append(fact.amountPlain()).append(';');
            }
            for (var driver : drivers) {
                canonical.append(driver.dimension()).append(':').append(driver.key()).append('=')
                        .append(driver.deltaPlain()).append(';');
            }
            canonical.append(forecastSummary).append('|').append(budgetRiskSummary).append('|')
                    .append(savingsSummary);
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Evidence fingerprint is unavailable", ex);
        }
    }

    /** Recomputes the snapshot fingerprint; any parse/normalization problem returns false. */
    static boolean verify(AdvisorJobMapper.SnapshotRow snapshot, tools.jackson.databind.ObjectMapper mapper) {
        try {
            var factsNode = mapper.readTree(snapshot.factsJson());
            var driversNode = mapper.readTree(snapshot.driversJson());
            var summaryNode = mapper.readTree(snapshot.summaryJson());
            if (factsNode == null || !factsNode.isArray() || driversNode == null || !driversNode.isArray()
                    || summaryNode == null || !summaryNode.isObject()) {
                return false;
            }
            var facts = new ArrayList<Fact>();
            for (var item : factsNode) {
                var id = item.path("factId").asText("");
                if (id.isBlank()) {
                    return false;
                }
                facts.add(new Fact(id, plain(item.path("amount").asText(""))));
            }
            var drivers = new ArrayList<Driver>();
            for (var item : driversNode) {
                var dimension = item.path("dimension").asText("");
                var key = item.path("key").asText("");
                if (dimension.isBlank() || key.isBlank()) {
                    return false;
                }
                drivers.add(new Driver(dimension, key, plain(item.path("deltaAmount").asText(""))));
            }
            var recomputed = fingerprint(snapshot.subjectType(), snapshot.subjectId(),
                    snapshot.currency(), snapshot.schemaVersion(), List.copyOf(facts),
                    List.copyOf(drivers), summaryNode.path("forecastSummary").asText(""),
                    summaryNode.path("budgetRiskSummary").asText(""),
                    summaryNode.path("savingsSummary").asText(""));
            return recomputed.equalsIgnoreCase(snapshot.evidenceFingerprint());
        } catch (Exception ex) {
            return false;
        }
    }

    private static String plain(String decimalText) {
        return new BigDecimal(decimalText.strip()).toPlainString();
    }
}
