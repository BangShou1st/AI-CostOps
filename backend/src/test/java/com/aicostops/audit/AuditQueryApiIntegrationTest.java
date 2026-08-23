package com.aicostops.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Audit event query API at the HTTP boundary: AUDIT_READ @ ORG is the only
 * way in, the query is pinned to the caller's own organization (the orgId
 * parameter can never widen it — no IDOR), filters and stable newest-first
 * pagination behave, and empty results are a valid empty page.
 */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class AuditQueryApiIntegrationTest extends AuditQueryTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void orgReaderReadsOwnOrganizationEvents() throws Exception {
        insertAuditEvent(orgId, readerUserId, "BUDGET_TOTAL_CHANGED",
                "{\"version\":3}", "2026-08-10 12:00:00.000000");

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].orgId").value(Long.toString(orgId)))
                .andExpect(jsonPath("$.items[0].eventType").value("BUDGET_TOTAL_CHANGED"))
                .andExpect(jsonPath("$.items[0].actorUserId").value(Long.toString(readerUserId)))
                .andExpect(jsonPath("$.items[0].subjectType").value("USER"))
                .andExpect(jsonPath("$.items[0].subjectId").value(Long.toString(readerUserId)))
                .andExpect(jsonPath("$.items[0].metadata.version").value(3))
                .andExpect(jsonPath("$.items[0].createdAt").isString());
    }

    @Test
    void withoutAuditReadIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", plainBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unrelatedReadPermissionDoesNotGrantAuditRead() throws Exception {
        // The plain member holds BUDGET_READ (a different ORG read grant);
        // any other READ must not open the audit log.
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", bearerOf(plainUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void foreignOrganizationAuditIsInvisible() throws Exception {
        insertAuditEvent(foreignOrgId, foreignReaderUserId, "LOGIN_SUCCESS",
                "{\"result\":\"SUCCESS\"}", "2026-08-10 12:00:00.000000");

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(foreignOrgId))
                        .header("Authorization", readerBearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(foreignOrgId))
                        .header("Authorization", foreignReaderBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void orgIdOutsideCallerScopeIsRejectedEvenWithoutAnyEvents() throws Exception {
        // The IDOR check runs before any data access: a caller whose only
        // grant is another organization's audit read gets a privacy 404, not
        // an empty page.
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", foreignReaderBearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventTypeFilterKeepsOnlyMatchingEvents() throws Exception {
        insertAuditEvent(orgId, readerUserId, "BUDGET_TOTAL_CHANGED",
                "{\"version\":1}", "2026-08-10 12:00:00.000000");
        insertAuditEvent(orgId, readerUserId, "ROLE_ASSIGNED",
                "{\"roleCode\":\"FIN\"}", "2026-08-11 12:00:00.000000");

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("eventType", "ROLE_ASSIGNED")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("ROLE_ASSIGNED"));
    }

    @Test
    void timeWindowFiltersByCreatedAt() throws Exception {
        insertAuditEvent(orgId, readerUserId, "EARLY_EVENT",
                "{}", "2026-08-01 00:00:00.000000");
        insertAuditEvent(orgId, readerUserId, "WINDOW_EVENT",
                "{}", "2026-08-15 00:00:00.000000");
        insertAuditEvent(orgId, readerUserId, "LATE_EVENT",
                "{}", "2026-09-01 00:00:00.000000");

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("from", "2026-08-02T00:00:00Z")
                        .param("to", "2026-08-20T00:00:00Z")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("WINDOW_EVENT"));

        // An inclusive lower bound keeps the boundary event itself.
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-08-01T00:00:00.000001Z")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("EARLY_EVENT"));
    }

    @Test
    void paginationIsStableAndNewestFirst() throws Exception {
        for (var i = 1; i <= 5; i++) {
            insertAuditEvent(orgId, readerUserId, "PAGE_TEST_" + i, "{}",
                    "2026-08-%02d 00:00:00.000000".formatted(i));
        }

        var mapper = new tools.jackson.databind.ObjectMapper();
        java.util.function.Function<String, java.util.List<String>> extract = body -> {
            try {
                var items = mapper.readTree(body).get("items");
                var names = new java.util.ArrayList<String>();
                items.forEach(item -> names.add(item.get("eventType").asString()));
                return names;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };

        var firstBody = mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        // Newest first: PAGE_TEST_5 leads page 0.
        org.assertj.core.api.Assertions.assertThat(extract.apply(firstBody))
                .containsExactly("PAGE_TEST_5", "PAGE_TEST_4");

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("page", "1")
                        .param("size", "2")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        // Repeating the same request returns identical bytes (stable order).
        var repeat = mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(repeat).isEqualTo(firstBody);
    }

    @Test
    void invalidPaginationReturnsValidationProblem() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .param("size", "201")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void emptyResultIsAValidEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId)))
                .andExpect(status().isUnauthorized());
    }

    private String bearerOf(long userId) {
        return "Bearer " + tokens.issue(userId, 7).token();
    }
}
