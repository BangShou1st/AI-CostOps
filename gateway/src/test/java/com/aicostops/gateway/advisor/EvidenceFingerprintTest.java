package com.aicostops.gateway.advisor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-module fingerprint contract (M18 round-4): the Gateway canonicalization must equal the
 * backend {@code AdvisorEvidence} canonicalization byte for byte. Both sides pin the shared
 * frozen vector below; any canonical drift fails CI on both modules.
 */
class EvidenceFingerprintTest {

    static final String FROZEN_VECTOR_HASH =
            "dd625a57b52d08ae4f89bab3d3213be4d18e684164562439dd258771b5571742";

    @Test
    void frozenVectorMatchesBackendCanonical() {
        var hash = EvidenceFingerprint.fingerprint("ANOMALY", 7L, "CNY", 2,
                List.of(new EvidenceFingerprint.Fact("anomaly:7:observed", "observed", "318.44", "CNY")),
                List.of(new EvidenceFingerprint.Driver("anomaly:7:driver:0", "PROVIDER", "prov-a", "12.50", "CNY")),
                "", "", "");
        assertEquals(FROZEN_VECTOR_HASH, hash);
    }

    @Test
    void verifyAcceptsIntactSnapshot() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
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
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"999.99\",\"currency\":\"CNY\"}]",
                "[]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }

    @Test
    void verifyRejectsTamperedLabel() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"tampered\","
                        + "\"amount\":\"318.44\",\"currency\":\"CNY\"}]",
                "[{\"id\":\"anomaly:7:driver:0\",\"dimension\":\"PROVIDER\","
                        + "\"key\":\"prov-a\",\"deltaAmount\":\"12.50\",\"currency\":\"CNY\"}]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }

    @Test
    void verifyRejectsTamperedFactCurrency() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"318.44\",\"currency\":\"USD\"}]",
                "[{\"id\":\"anomaly:7:driver:0\",\"dimension\":\"PROVIDER\","
                        + "\"key\":\"prov-a\",\"deltaAmount\":\"12.50\",\"currency\":\"CNY\"}]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }

    @Test
    void verifyRejectsTamperedDriverId() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"318.44\",\"currency\":\"CNY\"}]",
                "[{\"id\":\"anomaly:7:driver:9\",\"dimension\":\"PROVIDER\","
                        + "\"key\":\"prov-a\",\"deltaAmount\":\"12.50\",\"currency\":\"CNY\"}]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }

    @Test
    void verifyRejectsTamperedDriverCurrency() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"318.44\",\"currency\":\"CNY\"}]",
                "[{\"id\":\"anomaly:7:driver:0\",\"dimension\":\"PROVIDER\","
                        + "\"key\":\"prov-a\",\"deltaAmount\":\"12.50\",\"currency\":\"USD\"}]",
                "{\"forecastSummary\":\"\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }

    @Test
    void verifyRejectsTamperedSummary() {
        var mapper = new ObjectMapper();
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "ANOMALY", 7L, "CNY",
                "[{\"factId\":\"anomaly:7:observed\",\"label\":\"observed\","
                        + "\"amount\":\"318.44\",\"currency\":\"CNY\"}]",
                "[{\"id\":\"anomaly:7:driver:0\",\"dimension\":\"PROVIDER\","
                        + "\"key\":\"prov-a\",\"deltaAmount\":\"12.50\",\"currency\":\"CNY\"}]",
                "{\"forecastSummary\":\"tampered\",\"budgetRiskSummary\":\"\","
                        + "\"savingsSummary\":\"\"}",
                FROZEN_VECTOR_HASH, Instant.now(), Instant.now());
        assertFalse(EvidenceFingerprint.verify(snapshot, mapper));
    }
}
