package com.aicostops.advisor.application;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict validation for model-authored advisor narrative (M18 V3).
 *
 * <p>The response schema carries narrative fields only — no authoritative
 * money. Unknown fields (especially money-like ones) are rejected, lengths
 * are bounded, fact references must exist in the envelope, and control
 * characters are sanitized. Only validated narrative is persisted.
 */
@Component
public class AdvisorOutputValidator {

    public static final int MAX_SUMMARY = 2000;
    public static final int MAX_DRIVERS_EXPLANATION = 4000;
    public static final int MAX_ACTIONS = 20;
    public static final int MAX_ACTION_LEN = 500;
    public static final int MAX_WARNINGS = 10;
    public static final int MAX_WARNING_LEN = 500;

    private static final Set<String> MONEY_FIELD_FRAGMENTS = Set.of(
            "amount", "cost", "saving", "price", "budget", "forecast", "money", "total");

    private final ObjectMapper objectMapper;

    public AdvisorOutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidatedNarrative validate(String rawJson, Set<String> knownFactReferences) {
        if (rawJson == null || rawJson.isBlank() || rawJson.length() > 32768) {
            throw new IllegalArgumentException("Advisor output is missing or oversized.");
        }
        final JsonNode tree;
        try {
            tree = objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Advisor output schema is invalid.", ex);
        }
        if (tree == null || !tree.isObject()) {
            throw new IllegalArgumentException("Advisor output schema is invalid.");
        }
        var object = (ObjectNode) tree;
        for (var entry : object.properties()) {
            var field = entry.getKey();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Advisor output field is not allowed: " + field);
            }
            rejectMoneyField(field);
        }
        var summary = textField(object, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("Advisor summary is required.");
        }
        var refs = stringList(object, "factReferences");
        for (var ref : refs) {
            if (!knownFactReferences.contains(ref)) {
                throw new IllegalArgumentException("Advisor fact reference is unknown: " + ref);
            }
        }
        return new ValidatedNarrative(
                sanitize(summary, MAX_SUMMARY),
                sanitize(textField(object, "driversExplanation"), MAX_DRIVERS_EXPLANATION),
                sanitizeList(stringList(object, "recommendedActions"), MAX_ACTIONS, MAX_ACTION_LEN),
                sanitizeList(stringList(object, "warnings"), MAX_WARNINGS, MAX_WARNING_LEN),
                List.copyOf(refs));
    }

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "summary", "driversExplanation", "recommendedActions", "warnings", "factReferences");

    private void rejectMoneyField(String field) {
        var lower = field.toLowerCase(java.util.Locale.ROOT);
        for (var fragment : MONEY_FIELD_FRAGMENTS) {
            if (lower.contains(fragment)) {
                throw new IllegalArgumentException(
                        "Advisor output must not carry authoritative money: " + field);
            }
        }
    }

    private static String textField(ObjectNode object, String field) {
        var node = object.get(field);
        if (node == null || node.isNull()) return "";
        if (!node.isString()) throw new IllegalArgumentException("Advisor field is not text: " + field);
        return node.stringValue();
    }

    private static List<String> stringList(ObjectNode object, String field) {
        var node = object.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Advisor field is not a list: " + field);
        var values = new java.util.ArrayList<String>();
        for (var item : node) {
            if (!item.isString()) throw new IllegalArgumentException("Advisor list item is not text: " + field);
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private static String sanitize(String value, int max) {
        var cleaned = value.strip().replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private static List<String> sanitizeList(List<String> values, int maxItems, int maxLen) {
        var safe = values.size() <= maxItems ? values : values.subList(0, maxItems);
        return safe.stream().map(v -> sanitize(v == null ? "" : v, maxLen)).toList();
    }

    public record ValidatedNarrative(
            String summary,
            String driversExplanation,
            List<String> recommendedActions,
            List<String> warnings,
            List<String> factReferences) {
    }
}
