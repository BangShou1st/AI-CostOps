package com.aicostops.reporting;

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

/** Parsed contract evidence for the read-only workbench API. */
class M7OpenApiContractTest {

    private static Map<String, Object> document;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadDocument() throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var path = Path.of("..", "docs", "02-development", "api", "openapi.yaml");
        try (var input = Files.newInputStream(path)) {
            document = new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void workbenchOperationIsPublished() {
        var paths = (Map<String, Object>) document.get("paths");
        assertThat(paths).containsKey("/workbench");

        var workbench = (Map<String, Object>) paths.get("/workbench");
        var get = (Map<String, Object>) workbench.get("get");
        assertThat(get).isNotNull();
        var responses = (Map<String, Object>) get.get("responses");
        assertThat(responses).containsKeys("200", "400", "401", "403");
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditEventQueryOperationIsPublished() {
        var paths = (Map<String, Object>) document.get("paths");
        var auditEvents = (Map<String, Object>) paths.get("/audit-events");
        assertThat(auditEvents).as("path /audit-events").isNotNull();

        var get = (Map<String, Object>) auditEvents.get("get");
        assertThat(get).isNotNull();
        var responses = (Map<String, Object>) get.get("responses");
        assertThat(responses).containsKeys("200", "400", "401", "403");

        var parameters = (List<Map<String, Object>>) get.get("parameters");
        var names = parameters.stream()
                .map(parameter -> parameter.containsKey("$ref")
                        ? ((String) parameter.get("$ref")).substring(parameter.get("$ref").toString().lastIndexOf('/') + 1)
                        : (String) parameter.get("name"))
                .toList();
        assertThat(names).containsExactly("orgId", "eventType", "from", "to", "Page", "Size");

        var schemas = (Map<String, Object>) ((Map<String, Object>) document.get("components"))
                .get("schemas");
        assertThat(schema(schemas, "AuditEventResponse")).isNotNull();
        assertThat(schema(schemas, "PageResponseAuditEvent")).isNotNull();

        // BIGINT ids are strings per the global API convention.
        var auditEvent = schema(schemas, "AuditEventResponse");
        var properties = (Map<String, Object>) auditEvent.get("properties");
        assertThat((String) ((Map<String, Object>) properties.get("id")).get("$ref"))
                .isEqualTo("#/components/schemas/Id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void workbenchSchemaUsesMoneyStringsAndPermissionTrimmedSections() {
        var schemas = (Map<String, Object>) ((Map<String, Object>) document.get("components"))
                .get("schemas");

        assertThat(schema(schemas, "Money").get("pattern")).isEqualTo("^-?[0-9]+\\.[0-9]{8}$");

        var workbenchResponse = schema(schemas, "WorkbenchResponse");
        assertThat(workbenchResponse.get("type")).isEqualTo("object");
        var properties = (Map<String, Object>) workbenchResponse.get("properties");
        assertThat(properties).containsKeys("period", "costByProvider", "costByProject",
                "budgetVariance", "unallocatedCharges", "duplicateCandidates",
                "pendingApprovals", "openReconciliations", "closeStatus");

        // Amounts are Money strings everywhere; no section introduces a number.
        var costByProvider = (Map<String, Object>) properties.get("costByProvider");
        var items = (Map<String, Object>) costByProvider.get("items");
        var itemProperties = (Map<String, Object>) items.get("properties");
        assertThat((Map<String, Object>) itemProperties.get("totalAmount"))
                .containsEntry("$ref", "#/components/schemas/Money");

        var budgetVariance = (Map<String, Object>) properties.get("budgetVariance");
        var budgetItems = (Map<String, Object>) budgetVariance.get("items");
        var budgetFields = (Map<String, Object>) budgetItems.get("properties");
        assertThat(budgetFields.keySet()).containsExactlyInAnyOrder(
                "budgetId", "scopeType", "scopeId", "currency", "totalAmount",
                "actualAmount", "committedAmount", "availableAmount", "overBudget");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(Map<String, Object> schemas, String name) {
        var schema = (Map<String, Object>) schemas.get(name);
        assertThat(schema).as("schema %s", name).isNotNull();
        return schema;
    }
}
