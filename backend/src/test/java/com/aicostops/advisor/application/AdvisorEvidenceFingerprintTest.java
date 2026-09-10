package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-module fingerprint contract (M18 round-4): the backend canonicalization must equal the
 * Gateway {@code EvidenceFingerprint} canonicalization byte for byte. Both sides pin the shared
 * frozen vector below; any canonical drift fails CI on both modules. The canonical document
 * covers every prompt-visible field (fact id/label/amount/currency, driver id/dimension/key/
 * delta/currency, summaries, subject identity, envelope currency, schema version).
 */
class AdvisorEvidenceFingerprintTest {

    static final String FROZEN_VECTOR_HASH =
            "dd625a57b52d08ae4f89bab3d3213be4d18e684164562439dd258771b5571742";

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

    @Test
    void factLabelParticipatesInFingerprint() {
        var base = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(), "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        var tampered = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "tampered-label",
                        new BigDecimal("318.44"), "CNY")),
                List.of(), "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        assertNotEquals(AdvisorEvidence.fingerprint(base), AdvisorEvidence.fingerprint(tampered));
    }

    @Test
    void factAmountParticipatesInFingerprint() {
        var base = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(), "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        var tampered = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.45"), "CNY")),
                List.of(), "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        assertNotEquals(AdvisorEvidence.fingerprint(base), AdvisorEvidence.fingerprint(tampered));
    }

    @Test
    void driverDimensionKeyDeltaParticipateInFingerprint() {
        var base = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(new AdvisorEvidence.Driver("PROVIDER", "prov-a",
                        new BigDecimal("12.50"), "CNY")),
                "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        var tamperedDimension = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(new AdvisorEvidence.Driver("OTHER", "prov-a",
                        new BigDecimal("12.50"), "CNY")),
                "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        var tamperedKey = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(new AdvisorEvidence.Driver("PROVIDER", "prov-b",
                        new BigDecimal("12.50"), "CNY")),
                "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        var tamperedDelta = AdvisorEvidence.build("ANOMALY", 7L, "CNY",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:7:observed", "observed",
                        new BigDecimal("318.44"), "CNY")),
                List.of(new AdvisorEvidence.Driver("PROVIDER", "prov-a",
                        new BigDecimal("99.99"), "CNY")),
                "", "", "", Instant.parse("2026-09-11T00:00:00Z"));
        assertNotEquals(AdvisorEvidence.fingerprint(base), AdvisorEvidence.fingerprint(tamperedDimension));
        assertNotEquals(AdvisorEvidence.fingerprint(base), AdvisorEvidence.fingerprint(tamperedKey));
        assertNotEquals(AdvisorEvidence.fingerprint(base), AdvisorEvidence.fingerprint(tamperedDelta));
    }
}
