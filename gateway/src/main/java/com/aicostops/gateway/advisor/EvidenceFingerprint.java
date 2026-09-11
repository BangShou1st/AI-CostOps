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

    record Fact(String id, String label, String amountPlain, String currency) {
    }

    record Driver(String id, String dimension, String key, String deltaPlain, String currency) {
    }

    static String fingerprint(String subjectType, long subjectId, String currency, int schemaVersion,
            List<Fact> facts, List<Driver> drivers, String forecastSummary, String budgetRiskSummary,
            String savingsSummary) {
        try {
            var out = new StringBuilder(512);
            out.append("{\"schemaVersion\":").append(schemaVersion);
            out.append(",\"subjectType\":\"").append(jsonEscape(subjectType)).append('"');
            out.append(",\"subjectId\":").append(subjectId);
            out.append(",\"currency\":\"").append(jsonEscape(currency)).append('"');
            out.append(",\"facts\":[");
            for (var i = 0; i < facts.size(); i++) {
                var fact = facts.get(i);
                if (i > 0) out.append(',');
                out.append("{\"factId\":\"").append(jsonEscape(fact.id())).append('"');
                out.append(",\"label\":\"").append(jsonEscape(fact.label())).append('"');
                out.append(",\"amount\":\"").append(jsonEscape(fact.amountPlain())).append('"');
                out.append(",\"currency\":\"").append(jsonEscape(fact.currency())).append("\"}");
            }
            out.append("],\"drivers\":[");
            for (var i = 0; i < drivers.size(); i++) {
                var driver = drivers.get(i);
                if (i > 0) out.append(',');
                out.append("{\"id\":\"").append(jsonEscape(driver.id())).append('"');
                out.append(",\"dimension\":\"").append(jsonEscape(driver.dimension())).append('"');
                out.append(",\"key\":\"").append(jsonEscape(driver.key())).append('"');
                out.append(",\"deltaAmount\":\"").append(jsonEscape(driver.deltaPlain())).append('"');
                out.append(",\"currency\":\"").append(jsonEscape(driver.currency())).append("\"}");
            }
            out.append("],\"forecastSummary\":\"").append(jsonEscape(forecastSummary)).append('"');
            out.append(",\"budgetRiskSummary\":\"").append(jsonEscape(budgetRiskSummary)).append('"');
            out.append(",\"savingsSummary\":\"").append(jsonEscape(savingsSummary)).append("\"}");
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(out.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Evidence fingerprint is unavailable", ex);
        }
    }

    static String jsonEscape(String value) {
        if (value == null) return "";
        var out = new StringBuilder(value.length() + 16);
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == 34) {
                out.append((char) 92);
                out.append((char) 34);
            } else if (c == 92) {
                out.append((char) 92);
                out.append((char) 92);
            } else if (c == 8) {
                out.append((char) 92);
                out.append('b');
            } else if (c == 12) {
                out.append((char) 92);
                out.append('f');
            } else if (c == 10) {
                out.append((char) 92);
                out.append('n');
            } else if (c == 13) {
                out.append((char) 92);
                out.append('r');
            } else if (c == 9) {
                out.append((char) 92);
                out.append('t');
            } else if (c < 32) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
                var label = item.path("label").asText("");
                var currency = item.path("currency").asText("");
                if (id.isBlank() || label.isBlank() || currency.isBlank()) {
                    return false;
                }
                var amountText = item.path("amount").asText("");
                if (amountText.isBlank()) {
                    return false;
                }
                facts.add(new Fact(id, label, plain(amountText), currency));
            }
            var drivers = new ArrayList<Driver>();
            for (var item : driversNode) {
                var driverId = item.path("id").asText("");
                var dimension = item.path("dimension").asText("");
                var key = item.path("key").asText("");
                var currency = item.path("currency").asText("");
                if (driverId.isBlank() || dimension.isBlank() || key.isBlank() || currency.isBlank()) {
                    return false;
                }
                var deltaText = item.path("deltaAmount").asText("");
                if (deltaText.isBlank()) {
                    return false;
                }
                drivers.add(new Driver(driverId, dimension, key, plain(deltaText), currency));
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
