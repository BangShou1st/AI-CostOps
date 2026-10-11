package com.aicostops.gateway.advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 限制模型输出字段、引用及文本大小，拒绝未知事实引用。 */
@Component
public class AdvisorNarrativeValidator {
    private final ObjectMapper objectMapper;
    record ValidatedNarrative(String summary, String drivers, List<String> actions,
            List<String> warnings, List<String> refs) {}

    public AdvisorNarrativeValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ValidatedNarrative validateNarrative(String narrative, Set<String> knownRefs) {
        if (narrative == null || narrative.isBlank() || narrative.length() > 32768) {
            throw new IllegalArgumentException("Advisor narrative is missing or oversized.");
        }
        final ObjectNode tree;
        try {
            var parsed = objectMapper.readTree(narrative);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Not an object.");
            tree = (ObjectNode) parsed;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Advisor narrative is not JSON.", ex);
        }
        var allowed = Set.of("summary", "driversExplanation", "recommendedActions", "warnings",
                "factReferences");
        for (var entry : tree.properties()) {
            var field = entry.getKey().toLowerCase(Locale.ROOT);
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException("Advisor field not allowed: " + entry.getKey());
            }
            if (field.contains("amount") || field.contains("cost") || field.contains("saving")
                    || field.contains("price") || field.contains("budget") || field.contains("forecast")) {
                throw new IllegalArgumentException("Advisor must not carry money: " + entry.getKey());
            }
        }
        var summary = text(tree, "summary");
        if (summary.isBlank()) throw new IllegalArgumentException("Advisor summary required.");
        var refs = strings(tree, "factReferences");
        for (var ref : refs) {
            if (!knownRefs.contains(ref)) throw new IllegalArgumentException("Unknown fact ref: " + ref);
        }
        return new ValidatedNarrative(sanitize(summary, 2000),
                sanitize(text(tree, "driversExplanation"), 4000),
                strings(tree, "recommendedActions").stream().map(v -> sanitize(v, 500)).toList(),
                strings(tree, "warnings").stream().map(v -> sanitize(v, 500)).toList(), refs);
    }

    private static String text(ObjectNode tree, String field) {
        var node = tree.get(field);
        if (node == null || node.isNull()) return "";
        if (!node.isString()) throw new IllegalArgumentException("Not text: " + field);
        return node.stringValue();
    }

    private static List<String> strings(ObjectNode tree, String field) {
        var node = tree.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Not a list: " + field);
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isString()) throw new IllegalArgumentException("Not text item: " + field);
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private static String sanitize(String value, int max) {
        var cleaned = value.strip().replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
