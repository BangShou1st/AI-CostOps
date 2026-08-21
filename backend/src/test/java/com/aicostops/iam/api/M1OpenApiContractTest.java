package com.aicostops.iam.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class M1OpenApiContractTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Map<String, Set<String>> APPROVED_OPERATIONS = approvedOperations();
    private static Map<String, Object> document;

    @BeforeAll
    static void loadDocument() throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = Files.newInputStream(Path.of("..", "docs", "02-development", "api", "openapi.yaml"))) {
            document = new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @Test
    void openApiParses() {
        assertThat(document).containsEntry("openapi", "3.1.0");
        assertThat(map(document, "paths")).isNotEmpty();
    }

    @Test
    void openApiContainsExactlyApprovedM1Operations() {
        assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(APPROVED_OPERATIONS.keySet());
    }

    @Test
    void pagedContractsUseExistingPageShape() {
        var paged = Set.of("GET /users", "GET /projects", "GET /projects/{id}/members", "GET /teams",
                "GET /teams/{id}/members", "GET /cost-centers", "GET /provider-accounts",
                "GET /costs/charges", "GET /allocation-rules", "GET /budgets");
        for (var operation : paged) {
            assertThat(parameterRefs(operation)).contains(
                    "#/components/parameters/Page", "#/components/parameters/Size");
            assertThat(successSchemaRef(operation)).startsWith("#/components/schemas/PageResponse");
        }
        assertThat(schema("PageRequest").get("required")).isEqualTo(List.of("page", "size"));
        assertThat(schema("PageResponse").get("required"))
                .isEqualTo(List.of("items", "page", "size", "totalElements", "totalPages"));
    }

    @Test
    void catalogContractsAreUnpaged() {
        assertThat(parameterRefs("GET /roles")).isEmpty();
        assertThat(parameterRefs("GET /permissions")).isEmpty();
        assertThat(successSchemaRef("GET /roles")).isEqualTo("#/components/schemas/RoleCatalog");
        assertThat(successSchemaRef("GET /permissions")).isEqualTo("#/components/schemas/PermissionCatalog");
    }

    @Test
    void allocationTargetDirectoryIsAnUnpagedArrayOfSafeRefs() {
        assertThat(parameterRefs("GET /allocation-targets")).isEmpty();
        var success = map(operation("GET /allocation-targets"), "responses");
        var schema = map(map(map(success, "200"), "content"), "application/json");
        var items = map(schema.get("schema")).get("items");
        assertThat(map(items).get("$ref"))
                .isEqualTo("#/components/schemas/AllocationTargetResponse");
        assertThat(schema("AllocationTargetResponse").get("required"))
                .isEqualTo(List.of("type", "id", "name"));
        assertThat(propertyRef("AllocationTargetResponse", "id"))
                .isEqualTo("#/components/schemas/Id");
    }

    @Test
    void idsAndVersionsAreSerializedAsStrings() {
        assertStringSchema("Id");
        assertStringSchema("SecurityVersion");
        assertStringSchema("ExpectedVersion");
        assertThat(propertyRef("User", "securityVersion")).isEqualTo("#/components/schemas/SecurityVersion");
        assertThat(propertyRef("UpdateUserStatusRequest", "expectedVersion"))
                .isEqualTo("#/components/schemas/ExpectedVersion");
        assertThat(propertyRef("RoleAssignment", "scopeId")).isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("Membership", "organizationMemberId")).isEqualTo("#/components/schemas/Id");
    }

    @Test
    void approvalActionIdsUseCanonicalIdSchema() {
        // Both approval action schemas must use the canonical string Id schema,
        // not a long.
        for (var schemaName : List.of("ExpenseApprovalActionResponse", "CommitmentApprovalActionResponse")) {
            assertThat(propertyRef(schemaName, "id"))
                    .isEqualTo("#/components/schemas/Id");
            assertThat(propertyRef(schemaName, "actorMemberId"))
                    .isEqualTo("#/components/schemas/Id");
        }
        assertThat(propertyRef("CommitmentApprovalActionResponse", "approvalCaseId"))
                .isEqualTo("#/components/schemas/Id");
    }

    @Test
    void approvalHistoriesUseDistinctRuntimeAccurateSchemas() {
        assertThat(historyItemRef("ExpenseResponse"))
                .isEqualTo("#/components/schemas/ExpenseApprovalActionResponse");
        assertThat(historyItemRef("CommitmentResponse"))
                .isEqualTo("#/components/schemas/CommitmentApprovalActionResponse");
    }

    @Test
    void expenseApprovalActionResponseMatchesExpenseRuntimeShape() {
        assertThat(schema("ExpenseApprovalActionResponse").get("required"))
                .isEqualTo(List.of("id", "actionType", "actorMemberId", "fromState", "toState", "createdAt"));
        assertThat(propertyRef("ExpenseApprovalActionResponse", "id"))
                .isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("ExpenseApprovalActionResponse", "actionType"))
                .isEqualTo("#/components/schemas/ApprovalActionType");
        assertThat(propertyRef("ExpenseApprovalActionResponse", "actorMemberId"))
                .isEqualTo("#/components/schemas/Id");
        // The Expense runtime DTO has no approvalCaseId; the schema must not either.
        assertThat(properties("ExpenseApprovalActionResponse"))
                .doesNotContainKey("approvalCaseId");
    }

    @Test
    void commitmentApprovalActionResponseMatchesCommitmentRuntimeShape() {
        assertThat(schema("CommitmentApprovalActionResponse").get("required"))
                .isEqualTo(List.of("id", "approvalCaseId", "actorMemberId", "actionType", "fromState", "toState", "createdAt"));
        assertThat(propertyRef("CommitmentApprovalActionResponse", "id"))
                .isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("CommitmentApprovalActionResponse", "approvalCaseId"))
                .isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("CommitmentApprovalActionResponse", "actorMemberId"))
                .isEqualTo("#/components/schemas/Id");
    }

    @Test
    void problemResponsesUseProblemSchema() {
        var problem = map(map(map(document, "components"), "responses"), "Problem");
        var content = map(problem, "content");
        assertThat(map(map(content, "application/problem+json"), "schema").get("$ref"))
                .isEqualTo("#/components/schemas/Problem");
        assertThat(schema("Problem").get("required"))
                .isEqualTo(List.of("type", "title", "status", "detail", "instance", "code", "traceId"));
        for (var entry : operations().entrySet()) {
            var responses = map(entry.getValue(), "responses");
            for (var status : responses.keySet()) {
                if (!status.startsWith("2")) {
                    assertThat(map(responses, status).get("$ref"))
                            .as("%s %s", entry.getKey(), status)
                            .isEqualTo("#/components/responses/Problem");
                }
            }
        }
    }

    @Test
    void eachM1OperationDeclaresExactResponseStatuses() {
        assertThat(operationStatuses()).containsExactlyInAnyOrderEntriesOf(APPROVED_OPERATIONS);
    }

    @Test
    void importCommandsDeclareIdempotencyKeyHeaderParameter() {
        var idempotentCommands = Set.of(
                "POST /imports/{importId}/retry",
                "POST /imports/{importId}/cancel",
                "POST /imports/{importId}/confirm");
        for (var operation : idempotentCommands) {
            assertThat(parameterRefs(operation))
                    .as(operation)
                    .contains("#/components/parameters/IdempotencyKey");
        }
        var parameter = map(map(map(document, "components"), "parameters"), "IdempotencyKey");
        assertThat(parameter).containsEntry("name", "Idempotency-Key")
                .containsEntry("in", "header")
                .containsEntry("required", true);
        var schema = map(parameter, "schema");
        assertThat(schema.get("maxLength")).isEqualTo(200);
        assertThat(schema.get("minLength")).isEqualTo(1);
    }

    @Test
    void allocationCommandsDeclareIdempotencyKeyHeaderParameter() {
        var idempotentCommands = Set.of(
                "POST /costs/charges/{chargeFactId}/allocation-decisions/manual",
                "POST /allocation-decisions/{decisionId}/confirm",
                "POST /costs/charges/{chargeFactId}/allocation-proposal",
                "POST /allocation-rules/{ruleKey}/versions",
                "POST /allocation-rules/{ruleId}/archive");
        for (var operation : idempotentCommands) {
            assertThat(parameterRefs(operation))
                    .as(operation)
                    .contains("#/components/parameters/IdempotencyKey");
        }
        // PUT replace-lines is naturally idempotent: no key is required.
        assertThat(parameterRefs("PUT /allocation-decisions/{decisionId}/lines"))
                .doesNotContain("#/components/parameters/IdempotencyKey");
    }

    @Test
    void expenseCommandsDeclareIdempotencyKeyHeaderParameter() {
        var idempotentCommands = Set.of(
                "POST /expenses",
                "POST /expenses/{expenseId}/submit",
                "POST /expenses/{expenseId}/cancel",
                "POST /expenses/{expenseId}/request-info",
                "POST /expenses/{expenseId}/approve",
                "POST /expenses/{expenseId}/reject",
                "POST /expenses/{expenseId}/allocation-decisions/manual",
                "POST /allocation-decisions/{decisionId}/confirm");
        for (var operation : idempotentCommands) {
            assertThat(parameterRefs(operation))
                    .as(operation)
                    .contains("#/components/parameters/IdempotencyKey");
        }
        // PUT edit is a full-replacement CAS: no key is required.
        assertThat(parameterRefs("PUT /expenses/{expenseId}"))
                .doesNotContain("#/components/parameters/IdempotencyKey");
    }

    @Test
    void commitmentCommandsDeclareIdempotencyKeyHeaderParameter() {
        var idempotentCommands = Set.of(
                "POST /budgets/{budgetId}/commitments",
                "POST /commitments/{commitmentId}/approve",
                "POST /commitments/{commitmentId}/reject",
                "POST /commitments/{commitmentId}/cancel",
                "POST /commitments/{commitmentId}/release");
        for (var operation : idempotentCommands) {
            assertThat(parameterRefs(operation))
                    .as(operation)
                    .contains("#/components/parameters/IdempotencyKey");
        }
        // Consume is an internal primitive, not an HTTP operation at all.
        assertThat(operations()).doesNotContainKey("POST /commitments/{commitmentId}/consume");
    }

    @Test
    void m5LedgerContractsUseStableKeysStringIdsAndImmutableResponseShapes() {
        var commands = Set.of(
                "POST /costs/charges/{chargeFactId}/post",
                "POST /expenses/{expenseId}/post",
                "POST /ledger/corrections");
        for (var operation : commands) {
            assertThat(successSchemaRef(operation)).isEqualTo(operation.startsWith("POST /ledger/corrections")
                    ? "#/components/schemas/LedgerCorrectionResponse"
                    : "#/components/schemas/LedgerPostingDetailResponse");
        }
        assertThat(parameterRefs("POST /ledger/corrections"))
                .contains("#/components/parameters/IdempotencyKey");
        for (var operation : Set.of("GET /ledger/postings", "GET /ledger/entries")) {
            assertThat(parameterRefs(operation)).contains(
                    "#/components/parameters/Page", "#/components/parameters/Size");
            assertThat(successSchemaRef(operation)).startsWith("#/components/schemas/PageResponseLedger");
        }
        assertThat(propertyRef("LedgerEntryResponse", "id")).isEqualTo("#/components/schemas/Id");
        assertThat(propertyRef("LedgerEntryResponse", "amount")).isEqualTo("#/components/schemas/Money");
        assertThat(schema("ExpenseClaimStatus").get("enum"))
                .isEqualTo(List.of("DRAFT", "SUBMITTED", "NEEDS_INFO", "APPROVED", "POSTED", "REJECTED", "CANCELED"));
        assertThat(schema("CorrectionMode").get("enum"))
                .isEqualTo(List.of("REVERSAL_ONLY", "REPLACE"));
    }

    private static void assertStringSchema(String name) {
        assertThat(schema(name).get("type")).isEqualTo("string");
        assertThat(schema(name).get("pattern")).isEqualTo("^[0-9]+$");
    }

    private static String propertyRef(String schemaName, String propertyName) {
        return (String) map(map(schema(schemaName), "properties"), propertyName).get("$ref");
    }

    private static Map<String, Object> properties(String schemaName) {
        return map(schema(schemaName), "properties");
    }

    private static String historyItemRef(String schemaName) {
        var history = map(properties(schemaName), "history");
        return (String) map(history, "items").get("$ref");
    }

    private static Set<String> parameterRefs(String operation) {
        var parameters = list(operation(operation).getOrDefault("parameters", List.of()));
        var refs = new TreeSet<String>();
        for (var parameter : parameters) {
            // Inline parameters (filters etc.) carry no $ref and are skipped.
            if (map(parameter).get("$ref") instanceof String ref) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static String successSchemaRef(String operation) {
        var responses = map(operation(operation), "responses");
        var success = responses.entrySet().stream().filter(entry -> entry.getKey().startsWith("2"))
                .findFirst().orElseThrow().getValue();
        return (String) map(map(map(map(success), "content"), "application/json"), "schema").get("$ref");
    }

    private static Map<String, Object> operation(String operation) {
        return operations().get(operation);
    }

    private static Map<String, Map<String, Object>> operations() {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var path : map(document, "paths").entrySet()) {
            if (isOwnedByM6(path.getKey())) {
                continue;
            }
            for (var method : map(path.getValue()).entrySet()) {
                if (HTTP_METHODS.contains(method.getKey())) {
                    result.put(method.getKey().toUpperCase() + " " + path.getKey(), map(method.getValue()));
                }
            }
        }
        return result;
    }

    private static boolean isOwnedByM6(String path) {
        return path.startsWith("/reconciliation-")
                || path.startsWith("/billing-periods/{periodId}/");
    }

    private static Map<String, Set<String>> operationStatuses() {
        var result = new LinkedHashMap<String, Set<String>>();
        operations().forEach((name, operation) -> result.put(name, new TreeSet<>(map(operation, "responses").keySet())));
        return result;
    }

    private static Map<String, Object> schema(String name) {
        return map(map(map(document, "components"), "schemas"), name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Set<String>> approvedOperations() {
        var operations = new LinkedHashMap<String, Set<String>>();
        add(operations, "POST /auth/login", "200", "400", "401", "403", "429", "503");
        add(operations, "POST /auth/register", "200", "400", "403", "409", "503");
        add(operations, "POST /auth/refresh", "200", "401", "403", "409", "503");
        add(operations, "POST /auth/logout", "204", "401", "403", "503");
        add(operations, "POST /auth/logout-all", "204", "401");
        add(operations, "POST /auth/password/forgot", "202", "400", "429", "503");
        add(operations, "POST /auth/password/reset", "204", "400", "401", "403", "503");
        add(operations, "GET /auth/me", "200", "401");
        add(operations, "POST /invitations/{token}/accept", "200", "400", "409");
        add(operations, "GET /users", "200", "400", "401", "403");
        add(operations, "GET /users/{id}", "200", "400", "401", "403", "404");
        add(operations, "PATCH /users/{id}/status", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /roles", "200", "401", "403");
        add(operations, "GET /permissions", "200", "401", "403");
        add(operations, "POST /role-assignments", "201", "400", "401", "403", "404", "409");
        add(operations, "DELETE /role-assignments/{id}", "204", "401", "403", "404");
        add(operations, "POST /invitations", "201", "400", "401", "403", "409", "503");
        addCollection(operations, "/projects", true);
        addMaster(operations, "/projects/{id}");
        addMembers(operations, "/projects/{id}/members");
        addCollection(operations, "/teams", true);
        addMaster(operations, "/teams/{id}");
        addMembers(operations, "/teams/{id}/members");
        addCollection(operations, "/cost-centers", true);
        addMaster(operations, "/cost-centers/{id}");
        addCollection(operations, "/provider-accounts", true);
        addMaster(operations, "/provider-accounts/{id}");
        add(operations, "GET /evidence", "200", "400", "401", "403");
        add(operations, "GET /evidence/{evidenceId}", "200", "400", "401", "403", "404");
        add(operations, "GET /evidence/{id}/download", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /evidence/{evidenceId}/imports", "200", "400", "401", "403", "404");
        add(operations, "POST /provider-imports", "201", "400", "401", "403", "404", "409", "503");
        add(operations, "GET /imports", "200", "400", "401", "403");
        add(operations, "GET /imports/{importId}", "200", "400", "401", "403", "404");
        add(operations, "POST /imports/{importId}/retry", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /imports/{importId}/cancel", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /imports/{importId}/confirm", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /imports/{importId}/attempts", "200", "400", "401", "403", "404");
        add(operations, "GET /imports/{importId}/attempts/{attemptId}/issues", "200", "400", "401", "403", "404");
        add(operations, "GET /imports/{importId}/attempts/{attemptId}/raw-records", "200", "400", "401", "403", "404");
        add(operations, "GET /imports/{importId}/attempts/{attemptId}/raw-records/{recordId}", "200", "400", "401", "403", "404");
        add(operations, "POST /duplicate-candidates/scan", "200", "400", "401", "403", "409");
        add(operations, "GET /duplicate-candidates", "200", "400", "401", "403");
        add(operations, "GET /duplicate-candidates/{candidateId}", "200", "400", "401", "403", "404");
        add(operations, "POST /duplicate-candidates/{candidateId}/keep", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /duplicate-candidates/{candidateId}/exclude", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /costs/charges", "200", "400", "401", "403");
        add(operations, "GET /costs/charges/{chargeFactId}", "200", "400", "401", "403", "404");
        add(operations, "GET /costs/charges/{chargeFactId}/allocation-decisions", "200", "400", "401", "403", "404");
        add(operations, "GET /allocation-decisions/{decisionId}", "200", "400", "401", "403", "404");
        add(operations, "POST /costs/charges/{chargeFactId}/allocation-decisions/manual", "200", "400", "401", "403", "404", "409");
        add(operations, "PUT /allocation-decisions/{decisionId}/lines", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /allocation-decisions/{decisionId}/confirm", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /costs/charges/{chargeFactId}/allocation-proposal", "200", "400", "401", "403", "404", "409");
        // M4 expense workflow
        add(operations, "POST /expenses", "200", "400", "401", "403", "409");
        add(operations, "GET /expenses", "200", "400", "401", "403");
        add(operations, "GET /expenses/{expenseId}", "200", "400", "401", "403", "404");
        add(operations, "PUT /expenses/{expenseId}", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /expenses/{expenseId}/evidence", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /expenses/{expenseId}/evidence/download", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /expenses/{expenseId}/submit", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /expenses/{expenseId}/cancel", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /expense-reviews", "200", "400", "401", "403");
        add(operations, "GET /expense-reviews/{expenseId}", "200", "400", "401", "403", "404");
        add(operations, "POST /expenses/{expenseId}/request-info", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /expenses/{expenseId}/approve", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /expenses/{expenseId}/reject", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /expenses/{expenseId}/allocation-decisions", "200", "400", "401", "403", "404");
        add(operations, "POST /expenses/{expenseId}/allocation-decisions/manual", "200", "400", "401", "403", "404", "409");
        // M5 immutable ledger posting, query, and correction commands.
        add(operations, "POST /costs/charges/{chargeFactId}/post", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /expenses/{expenseId}/post", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /ledger/postings", "200", "400", "401", "403");
        add(operations, "GET /ledger/postings/{postingId}", "200", "400", "401", "403", "404");
        add(operations, "GET /ledger/entries", "200", "400", "401", "403");
        add(operations, "GET /ledger/entries/{entryId}", "200", "400", "401", "403", "404");
        add(operations, "POST /ledger/corrections", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /allocation-rules", "200", "400", "401", "403");
        add(operations, "GET /allocation-targets", "200", "400", "401", "403");
        add(operations, "GET /allocation-rules/{ruleId}", "200", "400", "401", "403", "404");
        add(operations, "POST /allocation-rules/{ruleKey}/versions", "200", "400", "401", "403", "409");
        add(operations, "POST /allocation-rules/{ruleId}/archive", "200", "400", "401", "403", "404", "409");
        // M4 budget management (natural identity create, version-CAS update)
        add(operations, "GET /budgets", "200", "400", "401", "403");
        add(operations, "POST /budgets", "201", "400", "401", "403", "409");
        add(operations, "GET /budgets/{budgetId}", "200", "400", "401", "403", "404");
        add(operations, "PUT /budgets/{budgetId}", "200", "400", "401", "403", "404", "409");
        // M4 budget commitments (AIC-044 request + AIC-045 lifecycle).
        add(operations, "POST /budgets/{budgetId}/commitments", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /commitments", "200", "400", "401", "403");
        add(operations, "GET /commitments/{commitmentId}", "200", "400", "401", "403", "404");
        add(operations, "POST /commitments/{commitmentId}/approve", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /commitments/{commitmentId}/reject", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /commitments/{commitmentId}/cancel", "200", "400", "401", "403", "404", "409");
        add(operations, "POST /commitments/{commitmentId}/release", "200", "400", "401", "403", "404", "409");
        add(operations, "GET /billing-periods", "200", "401", "403", "404");
        return Map.copyOf(operations);
    }

    private static void addCollection(Map<String, Set<String>> operations, String path, boolean create) {
        add(operations, "GET " + path, "200", "400", "401", "403");
        if (create) add(operations, "POST " + path, "201", "400", "401", "403", "409");
    }

    private static void addMaster(Map<String, Set<String>> operations, String path) {
        add(operations, "PATCH " + path, "200", "400", "401", "403", "404", "409");
    }

    private static void addMembers(Map<String, Set<String>> operations, String path) {
        add(operations, "GET " + path, "200", "400", "401", "403", "404");
        add(operations, "POST " + path, "201", "400", "401", "403", "404", "409");
        add(operations, "DELETE " + path + "/{memberId}", "204", "401", "403", "404", "409");
    }

    private static void add(Map<String, Set<String>> operations, String name, String... statuses) {
        operations.put(name, Set.of(statuses));
    }
}
