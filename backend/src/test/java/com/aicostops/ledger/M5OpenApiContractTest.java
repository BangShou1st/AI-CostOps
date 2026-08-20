package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Focused contract evidence for the M5 immutable-ledger surface. */
class M5OpenApiContractTest {

    private static Map<String, Object> document;
    private static String openapiText;

    @BeforeAll
    static void loadDocument() throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var path = Path.of("..", "docs", "02-development", "api", "openapi.yaml");
        openapiText = Files.readString(path);
        try (var input = Files.newInputStream(path)) {
            document = new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @Test
    void immutableLedgerOperationsAndLineageContractsArePublished() {
        assertThat(paths().keySet()).contains(
                "/costs/charges/{chargeFactId}/post",
                "/expenses/{expenseId}/post",
                "/ledger/postings",
                "/ledger/postings/{postingId}",
                "/ledger/entries",
                "/ledger/entries/{entryId}",
                "/ledger/corrections");

        assertThat(schema("AllocationLineResponse").get("required"))
                .isEqualTo(List.of("id", "lineIndex", "allocatedAmount", "currency"));
        assertThat(propertyRef("AllocationLineResponse", "id")).isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("LedgerEntryResponse", "amount")).isEqualTo("#/components/schemas/Money");
        assertThat(propertyRef("LedgerEntryResponse", "id")).isEqualTo("#/components/schemas/Id");
        assertThat(schema("CorrectionMode").get("enum")).isEqualTo(List.of("REVERSAL_ONLY", "REPLACE"));
        assertThat(map(map(schema("LedgerLineageResponse"), "properties")).keySet())
                .contains("correctedByCorrectionGroupId", "correctionTargetEntryId");
        assertThat(openapiText).doesNotContain("APPROVED-unallocated");
        assertThat(openapiText).contains("APPROVED remains until POSTED")
                .contains("ACTIVE same-org resources")
                .contains("do not inherit the old AllocationLine lineage")
                .contains("Invalid page or size returns ProblemDetail with code VALIDATION_FAILED");
    }

    private static Map<String, Object> paths() {
        return map(document, "paths");
    }

    private static Map<String, Object> schema(String name) {
        return map(map(map(document, "components"), "schemas"), name);
    }

    private static String propertyRef(String schemaName, String propertyName) {
        return (String) map(map(schema(schemaName), "properties"), propertyName).get("$ref");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }
}
