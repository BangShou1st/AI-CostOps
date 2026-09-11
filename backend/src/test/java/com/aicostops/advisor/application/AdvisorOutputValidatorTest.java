package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdvisorOutputValidatorTest {

    private final AdvisorOutputValidator validator = new AdvisorOutputValidator(new ObjectMapper());
    private final Set<String> refs = Set.of("fact-1", "fact-2");

    @Test
    void acceptsBoundedNarrative() {
        var validated = validator.validate(
                "{\"summary\":\"Spend rose on chat.\","
                + "\"driversExplanation\":\"Model X drove the delta.\","
                + "\"recommendedActions\":[\"Review routing.\"],"
                + "\"warnings\":[],\"factReferences\":[\"fact-1\"]}",
                refs);
        assertEquals("Spend rose on chat.", validated.summary());
        assertEquals(1, validated.factReferences().size());
    }

    @Test
    void rejectsAuthoritativeMoneyFields() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                "{\"summary\":\"ok\",\"savingAmount\":\"3.00\"}", refs));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                "{\"summary\":\"ok\",\"forecastAmount\":\"9.00\"}", refs));
    }

    @Test
    void rejectsUnknownFactReferences() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                "{\"summary\":\"ok\",\"factReferences\":[\"fact-9\"]}", refs));
    }

    @Test
    void rejectsMissingSummaryAndOversize() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("{\"driversExplanation\":\"x\"}", refs));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("x".repeat(40000), refs));
    }

    @Test
    void sanitizesControlCharacters() {
        var validated = validator.validate("{\"summary\":\"ok\\u0007yes\"}", refs);
        assertTrue(!validated.summary().contains("\u0007"));
    }

    @Test
    void evidenceFingerprintIsDeterministic() {
        var first = AdvisorEvidence.build("ANOMALY", 7L, "USD",
                java.util.List.of(new AdvisorEvidence.MoneyFact("fact-1", "Observed",
                        new java.math.BigDecimal("30.00"), "USD")),
                java.util.List.of(), "flat", "low", "none", java.time.Instant.parse("2026-09-09T00:00:00Z"));
        var second = AdvisorEvidence.build("ANOMALY", 7L, "USD",
                java.util.List.of(new AdvisorEvidence.MoneyFact("fact-1", "Observed",
                        new java.math.BigDecimal("30.00"), "USD")),
                java.util.List.of(), "flat", "low", "none", java.time.Instant.parse("2026-09-09T00:00:00Z"));
        assertEquals(AdvisorEvidence.fingerprint(first), AdvisorEvidence.fingerprint(second));
        assertEquals(64, AdvisorEvidence.fingerprint(first).length());
    }
}
