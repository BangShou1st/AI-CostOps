package com.aicostops.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Contract evidence for the governed pricing-rate money shape: input accepts a
 * JSON number or a plain decimal string, while responses stay scale-8 strings.
 */
class PricingRateInputOpenApiContractTest {

    private static Map<String, Object> document;

    @BeforeAll
    static void loadDocument() throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = Files.newInputStream(
                Path.of("..", "docs", "02-development", "api", "openapi.yaml"))) {
            document = new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @Test
    void unitPriceInputAcceptsNumberOrPlainDecimalString() {
        var unitPrice = property(schema("PricingRateInput"), "unitPrice");
        var alternatives = list(unitPrice.get("oneOf"));

        assertThat(alternatives).hasSize(2);
        assertThat(alternatives.get(0)).containsEntry("type", "number");
        assertThat(((Number) alternatives.get(0).get("minimum")).intValue()).isZero();
        assertThat(alternatives.get(1)).containsEntry("type", "string");

        var pattern = Pattern.compile((String) alternatives.get(1).get("pattern"));
        assertThat(List.of("0", "30", "30.00000000", "0.00000001"))
                .allMatch(value -> pattern.matcher(value).matches());
        assertThat(List.of("0.000000001", "-1", "1e-8"))
                .noneMatch(value -> pattern.matcher(value).matches());

        assertThat(unitPrice.get("description").toString())
                .contains("JSON number", "decimal string", "at most 8 fraction digits");
    }

    @Test
    void unitPriceResponseRemainsScale8DecimalString() {
        assertThat(property(schema("PricingRateResponse"), "unitPrice"))
                .containsEntry("type", "string")
                .containsEntry("pattern", "^-?[0-9]+\\.[0-9]{8}$");
    }

    private static Map<String, Object> schema(String name) {
        return map(map(map(document, "components"), "schemas"), name);
    }

    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return map(map(schema, "properties"), name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }
}
