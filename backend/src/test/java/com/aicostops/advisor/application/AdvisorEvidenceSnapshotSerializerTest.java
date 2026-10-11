package com.aicostops.advisor.application;

import static org.junit.jupiter.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdvisorEvidenceSnapshotSerializerTest {

    @Test
    void persistedStringsCanRebuildTheSameFingerprintAndDriverReferences() {
        var serializer = new AdvisorEvidenceSnapshotSerializer(new ObjectMapper());
        var envelope = AdvisorEvidence.build("ANOMALY", 42, "USD",
                List.of(new AdvisorEvidence.MoneyFact("anomaly:42:observed", "Observed \"金额\"\n",
                        new BigDecimal("80.00000000"), "USD")),
                List.of(new AdvisorEvidence.Driver("PROVIDER", "provider-金额",
                        new BigDecimal("-12.50000000"), "USD")),
                "forecast", "", "", Instant.parse("2026-10-11T00:00:00Z"));

        List<Map<String, Object>> facts = JsonPath.read(serializer.factsJson(envelope), "$");
        List<Map<String, Object>> drivers = JsonPath.read(serializer.driversJson(envelope), "$");
        Map<String, String> summary = JsonPath.read(serializer.summaryJson(envelope), "$");
        List<String> references = JsonPath.read(serializer.referencesJson(envelope), "$");
        assertEquals("80.00000000", facts.getFirst().get("amount"));
        assertEquals("-12.50000000", drivers.getFirst().get("deltaAmount"));
        assertEquals("anomaly:42:driver:0", drivers.getFirst().get("id"));
        assertEquals(List.of("anomaly:42:observed", "anomaly:42:driver:0"), references);
        assertEquals(Map.of("forecastSummary", "forecast", "budgetRiskSummary", "",
                "savingsSummary", ""), summary);

        // Rebuild from the persisted strings, as the Gateway worker does before dispatch.
        var rebuilt = AdvisorEvidence.build(envelope.subjectType(), envelope.subjectId(),
                envelope.currency(), facts.stream().map(row -> new AdvisorEvidence.MoneyFact(
                        (String) row.get("factId"), (String) row.get("label"),
                        new BigDecimal((String) row.get("amount")), (String) row.get("currency")))
                        .toList(),
                drivers.stream().map(row -> new AdvisorEvidence.Driver(
                        (String) row.get("dimension"), (String) row.get("key"),
                        new BigDecimal((String) row.get("deltaAmount")), (String) row.get("currency")))
                        .toList(),
                summary.get("forecastSummary"), summary.get("budgetRiskSummary"),
                summary.get("savingsSummary"), envelope.generatedAt());
        assertEquals(AdvisorEvidence.fingerprint(envelope), AdvisorEvidence.fingerprint(rebuilt));
    }
}
