package com.aicostops.gateway.advisor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-module fingerprint contract (M18 round-3): the Gateway canonicalization must equal the
 * backend {@code AdvisorEvidence} canonicalization byte for byte. Both sides pin the shared
 * frozen vector below; any canonical drift fails CI on both modules.
 */
class EvidenceFingerprintTest {

    static final String FROZEN_VECTOR_HASH =
            "9cf00c02e4ce7b9b4d943777f8ca0d36d959548e749e5048c3eee8581efe6036";

    @Test
    void frozenVectorMatchesBackendCanonical() {
        var hash = EvidenceFingerprint.fingerprint("ANOMALY", 7L, "CNY", 1,
                List.of(new EvidenceFingerprint.Fact("anomaly:7:observed", "318.44")),
                List.of(new EvidenceFingerprint.Driver("PROVIDER", "prov-a", "12.50")),
                "", "", "");
        assertEquals(FROZEN_VECTOR_HASH, hash);
    }

    @Test
    void verifyAcceptsIntactSnapshot() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 1, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"318.44\",\"currency\":\"CNY\"}]",
                "[{\"id\":\"anomaly:7:driver:0\",\"dimension\":\"PROVIDER\","
                        + "\"key\":\"prov-a\",\"deltaAmount\":\"12.50\",\"currency\":\"CNY\"}]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertTrue(EvidenceFingerprint.verify(snapshot, mapper));
    }

    @Test
    void verifyRejectsTamperedAmount() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 1, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"999.99\",\"currency\":\"CNY\"}]",
                "[]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }
}
