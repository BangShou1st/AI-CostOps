package com.aicostops.reconciliation;

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

/** Parsed contract evidence for the canonical reconciliation and period-close APIs. */
class M6OpenApiContractTest {

    private static Map<String, Object> document;

    @BeforeAll
    static void loadDocument() throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var path = Path.of("..", "docs", "02-development", "api", "openapi.yaml");
        try (var input = Files.newInputStream(path)) {
            document = new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @Test
    void reconciliationAndCloseOperationsArePublished() {
        assertThat(paths().keySet()).contains(
                "/reconciliation-runs",
                "/reconciliation-runs/{runId}",
                "/reconciliation-cases",
                "/reconciliation-cases/{caseId}",
                "/reconciliation-cases/{caseId}/investigate",
                "/reconciliation-cases/{caseId}/return-open",
                "/reconciliation-cases/{caseId}/resolve",
                "/billing-periods/{periodId}/close-readiness",
                "/billing-periods/{periodId}/close-runs",
                "/billing-periods/{periodId}/close-runs/{runId}",
                "/billing-periods/{periodId}/close",
                "/billing-periods/{periodId}/reopen");

        assertThat(schema("Money").get("type")).isEqualTo("string");
        assertThat(schema("Money").get("pattern")).isEqualTo("^-?[0-9]+\\.[0-9]{8}$");
        assertThat(schema("Id").get("type")).isEqualTo("string");
        assertThat(schema("ReconciliationRunStatus").get("enum"))
                .isEqualTo(List.of("CREATED", "RUNNING", "COMPLETED", "FAILED"));
        assertThat(schema("CloseBlockerCode").get("enum"))
                .isEqualTo(List.of("OPEN_IMPORTS", "UNRESOLVED_DUPLICATES",
                        "UNALLOCATED_CHARGES", "UNPOSTED_APPROVED_EXPENSES",
                        "OPEN_MATERIAL_RECONCILIATION", "PENDING_CORRECTIONS",
                        "LEDGER_INTEGRITY"));
    }

    @Test
    void reconciliationAndCloseSchemasUseStringIdsMoneyAndDateTimes() {
        assertThat(schema("ReconciliationRunResponse").get("required")).isEqualTo(List.of(
                "id", "billingPeriodId", "status", "algorithmVersion", "toleranceAmount",
                "basisHash", "summary", "createdByMemberId", "startedAt", "createdAt", "updatedAt"));
        assertThat(propertyRef("ReconciliationRunResponse", "id")).isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("ReconciliationRunResponse", "toleranceAmount"))
                .isEqualTo("#/components/schemas/Money");
        assertThat(propertyFormat("ReconciliationRunResponse", "startedAt")).isEqualTo("date-time");
        assertThat(propertyRef("ReconciliationCaseResponse", "differenceAmount"))
                .isEqualTo("#/components/schemas/Money");
        assertThat(propertyRef("CloseRunResponse", "billingPeriodId"))
                .isEqualTo("#/components/schemas/Id");
        assertThat(propertyFormat("CloseRunResponse", "startedAt")).isEqualTo("date-time");
        assertThat(propertyRef("ReopenPeriodRequest", "reasonCode")).isNull();
        assertThat(schema("ReopenPeriodRequest").get("required"))
                .isEqualTo(List.of("reasonCode", "reasonNote"));
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

    private static String propertyFormat(String schemaName, String propertyName) {
        return (String) map(map(schema(schemaName), "properties"), propertyName).get("format");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }
}
