package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-module fingerprint contract (M18 round-3): the backend canonicalization must equal the
 * Gateway {@code EvidenceFingerprint} canonicalization byte for byte. Both sides pin the shared
 * frozen vector below; any canonical drift fails CI on both modules.
 */
class AdvisorEvidenceFingerprintTest {

    static final String FROZEN_VECTOR_HASH =
            "9cf00c02e4ce7b9b4d943777f8ca0d36d959548e749e5048c3eee8581efe6036";

    @Test
    void frozenVectorMatchesGatewayCanonical() {
        var envelope = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(new AdvisorEvidence.Driver("PROVIDER", "prov-a",
                        new BigDecimal("12.50"), "CNY")),
                "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        assertEquals(FROZEN_VECTOR_HASH, AdvisorEvidence.fingerprint(envelope));
    }

    @Test
    void summariesParticipateInFingerprint() {
        var base = AdvisorEvidence.build("FORECAST", 9L, "USD",
                List.of(new AdvisorEvidence.MoneyFact("forecast:9:projected", "projected",
                        new BigDecimal("10.00"), "USD")),
                List.of(), "method=X", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        var altered = AdvisorEvidence.build("FORECAST", 9L, "USD",
                List.of(new AdvisorEvidence.MoneyFact("forecast:9:projected", "projected",
                        new BigDecimal("10.00"), "USD")),
                List.of(), "method=Y", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        assertNotEquals(AdvisorEvidence.fingerprint(base), AdvisorEvidence.fingerprint(altered));
    }
}
