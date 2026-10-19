package com.aicostops.gateway.advisor;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** 从冻结证据生成提示词及引用集合，不重新计算金额或风险。 */
@Component
public class AdvisorSnapshotPromptBuilder {
    private final ObjectMapper objectMapper;
    private static final Logger LOG = LoggerFactory.getLogger(AdvisorSnapshotPromptBuilder.class);

    public AdvisorSnapshotPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String buildPrompt(AdvisorJobMapper.SnapshotRow snapshot) {
        try {
            var factsNode = objectMapper.readTree(snapshot.factsJson());
            var driversNode = objectMapper.readTree(snapshot.driversJson());
            var summaryNode = objectMapper.readTree(snapshot.summaryJson());
            if (factsNode == null || !factsNode.isArray() || factsNode.isEmpty()
                    || driversNode == null || !driversNode.isArray()
                    || summaryNode == null || !summaryNode.isObject()) {
                return null;
            }
            var envelope = objectMapper.createObjectNode();
            envelope.put("schema_version", snapshot.schemaVersion());
            envelope.put("subject", snapshot.subjectType());
            envelope.put("subject_id", snapshot.subjectId());
            envelope.put("currency", snapshot.currency());
            var factsArray = envelope.putArray("facts");
            for (var fact : factsNode) {
                var id = fact.path("factId").asText("");
                var label = fact.path("label").asText("");
                var amount = fact.path("amount").asText("");
                if (id.isBlank() || label.isBlank() || amount.isBlank()) {
                    return null;
                }
                var node = objectMapper.createObjectNode();
                node.put("id", id);
                node.put("label", label);
                node.put("amount", amount);
                node.put("currency", fact.path("currency").asText(snapshot.currency()));
                factsArray.add(node);
            }
            var driversArray = envelope.putArray("drivers");
            for (var driver : driversNode) {
                var id = driver.path("id").asText("");
                var dimension = driver.path("dimension").asText("");
                var key = driver.path("key").asText("");
                var delta = driver.path("deltaAmount").asText("");
                if (id.isBlank() || dimension.isBlank() || key.isBlank() || delta.isBlank()) {
                    return null;
                }
                var node = objectMapper.createObjectNode();
                node.put("id", id);
                node.put("dimension", dimension);
                node.put("key", key);
                node.put("deltaAmount", delta);
                node.put("currency", driver.path("currency").asText(snapshot.currency()));
                driversArray.add(node);
            }
            envelope.put("forecastSummary", summaryNode.path("forecastSummary").asText(""));
            envelope.put("savingsSummary", summaryNode.path("savingsSummary").asText(""));
            var instruction = new StringBuilder("Explain the cost facts briefly. Reply with JSON object "
                    + "containing only: summary, driversExplanation, recommendedActions (array of strings), "
                    + "warnings (array of strings), factReferences (array of strings). "
                    + "Never invent amounts; never include money fields. "
                    + "factReferences may only contain the fact/driver IDs listed above. "
                    + "Drivers are deterministic analysis results; never invent drivers.");
            if ("BUDGET_RISK".equals(snapshot.subjectType())) {
                final JsonNode risk;
                try {
                    risk = objectMapper.readTree(summaryNode.path("budgetRiskSummary").asText(""));
                } catch (Exception ex) {
                    return null;
                }
                if (risk == null || !risk.isObject() || risk.path("risk").asText("").isBlank()) {
                    return null;
                }
                var riskNode = objectMapper.createObjectNode();
                for (var field : List.of("scope", "actual", "committed", "reservations",
                        "forecastFuture", "immediate", "projected", "total", "risk", "forecastAvailable")) {
                    riskNode.put(field, risk.path(field).asText(""));
                }
                envelope.set("budgetRisk", riskNode);
                instruction.append(" The budget risk inputs and classification are precomputed; "
                        + "explain them only and never recompute projected spend or risk. "
                        + "If forecastAvailable is false or unspecified, future costs are unknown; "
                        + "a zero fallback is not evidence of zero future spending. State this limitation.");
            } else {
                envelope.put("budgetRiskSummary", summaryNode.path("budgetRiskSummary").asText(""));
            }
            envelope.put("instruction", instruction.toString());
            return objectMapper.writeValueAsString(envelope);
        } catch (RuntimeException ex) {
            LOG.warn("Advisor prompt build failed for snapshot {}", snapshot.id(), ex);
            return null;
        }
    }

    Set<String> parseRefs(String refsJson) {
        try {
            var refs = new java.util.HashSet<String>();
            var raw = refsJson == null ? "[]" : refsJson;
            var node = objectMapper.readTree(raw);
            if (node != null && node.isArray()) {
                for (var item : node) refs.add(item.stringValue());
            }
            return Set.copyOf(refs);
        } catch (Exception ex) {
            return Set.of();
        }
    }

    Set<String> snapshotDerivedRefs(AdvisorJobMapper.SnapshotRow snapshot) {
        try {
            var refs = new java.util.HashSet<String>();
            var factsNode = objectMapper.readTree(snapshot.factsJson());
            var driversNode = objectMapper.readTree(snapshot.driversJson());
            if (factsNode == null || !factsNode.isArray() || driversNode == null || !driversNode.isArray()) {
                throw new IllegalArgumentException("Advisor snapshot refs are unreadable");
            }
            for (var item : factsNode) {
                var id = item.path("factId").asText("");
                if (id.isBlank()) {
                    throw new IllegalArgumentException("Advisor snapshot fact ref is missing");
                }
                refs.add(id);
            }
            for (var item : driversNode) {
                var id = item.path("id").asText("");
                if (id.isBlank()) {
                    throw new IllegalArgumentException("Advisor snapshot driver ref is missing");
                }
                refs.add(id);
            }
            return Set.copyOf(refs);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Advisor snapshot refs are unreadable", ex);
        }
    }
}
