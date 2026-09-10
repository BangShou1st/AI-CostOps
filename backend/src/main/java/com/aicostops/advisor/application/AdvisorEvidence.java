package com.aicostops.advisor.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded typed Advisor evidence envelope (M18 V3).
 *
 * <p>The model receives only deterministic backend-computed facts — never a
 * database dump, workload prompts or raw provider bytes. All money is
 * precomputed {@link BigDecimal}; the model authors narrative only.
 */
public final class AdvisorEvidence {

    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_FACTS = 20;
    public static final int MAX_DRIVERS = 10;
    public static final int MAX_TEXT = 500;

    private AdvisorEvidence() {
    }

    public record MoneyFact(String factId, String label, BigDecimal amount, String currency) {
    }

    public record Driver(String dimension, String key, BigDecimal deltaAmount, String currency) {
    }

    public record Envelope(
            String subjectType,
            long subjectId,
            String currency,
            List<MoneyFact> facts,
            List<Driver> drivers,
            String forecastSummary,
            String budgetRiskSummary,
            String savingsSummary,
            List<String> factReferenceIds,
            Instant generatedAt,
            int schemaVersion) {
    }

    public static Envelope build(
            String subjectType,
            long subjectId,
            String currency,
            List<MoneyFact> facts,
            List<Driver> drivers,
            String forecastSummary,
            String budgetRiskSummary,
            String savingsSummary,
            Instant generatedAt) {
        var safeSubject = required(bounded(subjectType, 100), "Subject type is required.");
        var safeCurrency = required(currency, "Currency is required.");
        if (!safeCurrency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be ISO-4217.");
        }
        var safeFacts = boundedList(facts == null ? List.of() : facts, MAX_FACTS).stream()
                .map(f -> new MoneyFact(required(bounded(f.factId(), 100), "Fact id required."),
                        bounded(f.label() == null ? "" : f.label(), MAX_TEXT),
                        Objects.requireNonNull(f.amount(), "Fact amount required."), safeCurrency))
                .toList();
        var safeDrivers = boundedList(drivers == null ? List.of() : drivers, MAX_DRIVERS).stream()
                .map(d -> new Driver(bounded(d.dimension(), 64), bounded(d.key(), 200),
                        Objects.requireNonNull(d.deltaAmount(), "Driver delta required."), safeCurrency))
                .toList();
        var refs = safeFacts.stream().map(MoneyFact::factId).toList();
        return new Envelope(safeSubject.toUpperCase(Locale.ROOT), subjectId, safeCurrency,
                List.copyOf(safeFacts), List.copyOf(safeDrivers),
                bounded(forecastSummary, MAX_TEXT), bounded(budgetRiskSummary, MAX_TEXT),
                bounded(savingsSummary, MAX_TEXT), refs,
                generatedAt == null ? Instant.now() : generatedAt, SCHEMA_VERSION);
    }

    /**
     * Deterministic fingerprint binding one job attempt to its evidence. The canonical form is one
     * frozen JSON document covering every semantically meaningful snapshot field that can reach
     * the model prompt (subject identity, envelope currency, per-fact id/label/amount/currency,
     * per-driver reference id/dimension/key/delta/currency, and all three summaries), so any
     * tampering with the frozen snapshot is detectable by the Gateway worker before Provider I/O.
     * The Gateway module mirrors this exact canonicalization (see {@code EvidenceFingerprint});
     * the cross-module contract test pins it. Amounts are {@link BigDecimal#toPlainString}.
     * DB surrogate ids, timestamps, JSON whitespace and storage formatting are never hashed.
     */
    public static String fingerprint(Envelope envelope) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalForm(envelope).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Evidence fingerprint is unavailable", ex);
        }
    }

    /** Exact canonical JSON hashed by {@link #fingerprint}; mirrored by the Gateway worker. */
    static String canonicalForm(Envelope envelope) {
        var out = new StringBuilder(512);
        out.append("{\"schemaVersion\":").append(envelope.schemaVersion());
        out.append(",\"subjectType\":\"").append(jsonEscape(envelope.subjectType())).append('"');
        out.append(",\"subjectId\":").append(envelope.subjectId());
        out.append(",\"currency\":\"").append(jsonEscape(envelope.currency())).append('"');
        out.append(",\"facts\":[");
        var facts = envelope.facts();
        for (var i = 0; i < facts.size(); i++) {
            var fact = facts.get(i);
            if (i > 0) out.append(',');
            out.append("{\"factId\":\"").append(jsonEscape(fact.factId())).append('"');
            out.append(",\"label\":\"").append(jsonEscape(fact.label())).append('"');
            out.append(",\"amount\":\"").append(jsonEscape(fact.amount().toPlainString())).append('"');
            out.append(",\"currency\":\"").append(jsonEscape(fact.currency())).append("\"}");
        }
        out.append("],\"drivers\":[");
        var drivers = envelope.drivers();
        for (var i = 0; i < drivers.size(); i++) {
            var driver = drivers.get(i);
            var refId = envelope.subjectType().toLowerCase(Locale.ROOT) + ":" + envelope.subjectId()
                    + ":driver:" + i;
            if (i > 0) out.append(',');
            out.append("{\"id\":\"").append(jsonEscape(refId)).append('"');
            out.append(",\"dimension\":\"").append(jsonEscape(driver.dimension())).append('"');
            out.append(",\"key\":\"").append(jsonEscape(driver.key())).append('"');
            out.append(",\"deltaAmount\":\"").append(jsonEscape(driver.deltaAmount().toPlainString())).append('"');
            out.append(",\"currency\":\"").append(jsonEscape(driver.currency())).append("\"}");
        }
        out.append("],\"forecastSummary\":\"").append(jsonEscape(envelope.forecastSummary())).append('"');
        out.append(",\"budgetRiskSummary\":\"").append(jsonEscape(envelope.budgetRiskSummary())).append('"');
        out.append(",\"savingsSummary\":\"").append(jsonEscape(envelope.savingsSummary())).append("\"}");
        return out.toString();
    }

    /** JSON string escape matching the Gateway mirror byte for byte. */
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

    private static String bounded(String value, int max) {
        if (value == null) return "";
        var trimmed = value.strip().replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private static <T> List<T> boundedList(List<T> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }
}
