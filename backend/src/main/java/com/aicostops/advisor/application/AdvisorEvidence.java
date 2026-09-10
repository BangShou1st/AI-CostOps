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

    public static final int SCHEMA_VERSION = 1;
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
     * Deterministic fingerprint binding one job attempt to its evidence. The canonical form covers
     * subject identity, money facts, deterministic drivers AND the summary block (which carries
     * the precomputed budget-risk classification), so any tampering with the frozen snapshot is
     * detectable by the Gateway worker before Provider I/O. The Gateway module mirrors this exact
     * canonicalization (see {@code EvidenceFingerprint}); the cross-module contract test pins it.
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

    /** Exact canonical string hashed by {@link #fingerprint}; mirrored by the Gateway worker. */
    static String canonicalForm(Envelope envelope) {
        var canonical = new StringBuilder(envelope.subjectType()).append('|')
                .append(envelope.subjectId()).append('|').append(envelope.currency()).append('|')
                .append(envelope.schemaVersion()).append('|');
        for (var fact : envelope.facts()) {
            canonical.append(fact.factId()).append('=')
                    .append(fact.amount().toPlainString()).append(';');
        }
        for (var driver : envelope.drivers()) {
            canonical.append(driver.dimension()).append(':').append(driver.key()).append('=')
                    .append(driver.deltaAmount().toPlainString()).append(';');
        }
        canonical.append(envelope.forecastSummary()).append('|')
                .append(envelope.budgetRiskSummary()).append('|')
                .append(envelope.savingsSummary());
        return canonical.toString();
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
