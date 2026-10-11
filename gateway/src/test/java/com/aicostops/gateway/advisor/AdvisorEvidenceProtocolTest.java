package com.aicostops.gateway.advisor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AdvisorEvidenceProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AdvisorSnapshotPromptBuilder prompts = new AdvisorSnapshotPromptBuilder(json);
    private final AdvisorNarrativeValidator narratives = new AdvisorNarrativeValidator(json);

    @Test
    void budgetPromptPreservesFrozenAmountsRiskAndReferences() {
        var risk = java.util.Map.of("scope", "TEAM", "actual", "10.00", "committed", "20.00",
                "reservations", "5.00", "forecastFuture", "7.00", "immediate", "35.00",
                "projected", "42.00", "total", "100.00", "risk", "FROZEN_RISK");
        var summary = json.writeValueAsString(java.util.Map.of("forecastSummary", "",
                "savingsSummary", "", "budgetRiskSummary", json.writeValueAsString(risk)));
        var snapshot = new AdvisorJobMapper.SnapshotRow(1L, 7L, 100L, 2, "BUDGET_RISK", 3L, "USD",
                """
                [{"factId":"budget:3:actual","label":"actual","amount":"10.00","currency":"USD"},
                 {"factId":"budget:3:committed","label":"committed","amount":"20.00","currency":"USD"}]
                """, "[]",
                summary, "unused-by-builder", Instant.EPOCH, Instant.EPOCH);
        var prompt = json.readTree(prompts.buildPrompt(snapshot));
        var budget = prompt.get("budgetRisk");
        assertEquals("5.00", budget.get("reservations").stringValue());
        assertEquals("7.00", budget.get("forecastFuture").stringValue());
        assertEquals("35.00", budget.get("immediate").stringValue());
        assertEquals("42.00", budget.get("projected").stringValue());
        // The model receives the persisted classification, without recomputing it.
        assertEquals("FROZEN_RISK", budget.get("risk").stringValue());
        assertEquals("10.00", prompt.get("facts").get(0).get("amount").stringValue());
        assertEquals(Set.of("budget:3:actual", "budget:3:committed"),
                prompts.snapshotDerivedRefs(snapshot));
    }

    @Test
    void narrativeRejectsInventedReferencesAndMoneyFields() {
        assertThrows(IllegalArgumentException.class, () -> narratives.validateNarrative(
                "{\"summary\":\"Review\",\"factReferences\":[\"foreign:1\"]}", Set.of("budget:3:actual")));
        assertThrows(IllegalArgumentException.class, () -> narratives.validateNarrative(
                "{\"summary\":\"Review\",\"amount\":100}", Set.of()));
        var validated = narratives.validateNarrative(
                "{\"summary\":\"Review\",\"factReferences\":[\"budget:3:actual\"]}",
                Set.of("budget:3:actual"));
        assertEquals("Review", validated.summary());
        assertEquals(java.util.List.of("budget:3:actual"), validated.refs());
    }
}
