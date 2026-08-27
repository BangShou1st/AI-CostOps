package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Allocation rule version lifecycle: server-authoritative versions, append-only
 * definitions, archive, idempotency, and ALLOCATION_RULE_MANAGE enforcement.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class AllocationRuleApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private static final String VERSION_BODY = """
            {"name":"OpenAI API key rule","providerCode":"GLM",
             "matchHintType":"PROVIDER_API_KEY","matchValue":"key-abc-123",
             "priority":10,"targetProjectId":"%d",
             "effectiveFrom":"2026-01-01T00:00:00Z"}
            """;

    @Test
    void firstVersionOfNewKeyIsVersionOne() throws Exception {
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "openai-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.ruleKey").value("openai-key"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("OpenAI API key rule"))
                .andExpect(jsonPath("$.providerCode").value("GLM"))
                .andExpect(jsonPath("$.matchHintType").value("PROVIDER_API_KEY"))
                .andExpect(jsonPath("$.matchValue").value("key-abc-123"))
                .andExpect(jsonPath("$.priority").value(10))
                .andExpect(jsonPath("$.targetProjectId").value(Long.toString(projectId)))
                .andExpect(jsonPath("$.effectiveFrom").exists())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdByMemberId").value(Long.toString(actorMemberId)));
    }

    @Test
    void newVersionOfExistingKeyIsMaxPlusOne() throws Exception {
        // Adjacent half-open ranges: overlapping ACTIVE ranges of one key are
        // rejected, so the fixture stays inside the overlap invariant.
        var firstRange = VERSION_BODY.formatted(projectId).replace(
                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                        + "\"effectiveTo\":\"2026-02-01T00:00:00Z\"");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "glm-project")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-v2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRange))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        var secondRange = VERSION_BODY.formatted(projectId).replace(
                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                "\"effectiveFrom\":\"2026-02-01T00:00:00Z\","
                        + "\"effectiveTo\":\"2026-03-01T00:00:00Z\"");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "glm-project")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-v2b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondRange))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM allocation_rule
                        WHERE org_id=? AND rule_key='glm-project'
                        """, Integer.class, orgId)).isEqualTo(2);
    }

    @Test
    void createVersionRejectsInvalidDefinitions() throws Exception {
        var blankName = VERSION_BODY.formatted(projectId)
                .replace("\"name\":\"OpenAI API key rule\"", "\"name\":\"  \"");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-rule")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-bad1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var badPriority = VERSION_BODY.formatted(projectId)
                .replace("\"priority\":10", "\"priority\":10000");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-rule")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-bad2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPriority))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var noTarget = VERSION_BODY.formatted(projectId)
                .replace(",\"targetProjectId\":\"" + projectId + "\"", "");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-rule")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-bad3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noTarget))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var reversedPeriod = VERSION_BODY.formatted(projectId)
                .replace("\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                        "\"effectiveFrom\":\"2026-02-01T00:00:00Z\","
                                + "\"effectiveTo\":\"2026-01-01T00:00:00Z\"");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-rule")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-bad4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversedPeriod))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // unknown match type
        var badMatchType = VERSION_BODY.formatted(projectId)
                .replace("PROVIDER_API_KEY", "REGEX");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-rule")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-bad5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badMatchType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createVersionRejectsInactiveTargetAndForeignProviderAccount() throws Exception {
        deactivateTarget("project", orgId, projectId);
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-target")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-target")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var foreignAccount = insertProviderAccount(foreignOrgId, "GLM");
        var accountBody = VERSION_BODY.formatted(projectId)
                .replace("\"providerCode\":\"GLM\",",
                        "\"providerCode\":\"GLM\",\"providerAccountId\":\"" + foreignAccount + "\",");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-account")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // provider code mismatch on a same-org account
        var wrongCodeAccount = insertProviderAccount(orgId, "OPENAI");
        var codeMismatchBody = VERSION_BODY.formatted(projectId)
                .replace("\"providerCode\":\"GLM\",",
                        "\"providerCode\":\"GLM\",\"providerAccountId\":\""
                                + wrongCodeAccount + "\",");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-account")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-account2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(codeMismatchBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // provider code letter case is also an exact mismatch: an account
        // registered as 'glm' must not satisfy a GLM rule constraint. The
        // project target is reactivated so the rejection can only come from
        // the account's provider code.
        jdbc.update("UPDATE project SET status='ACTIVE' WHERE org_id=? AND id=?", orgId, projectId);
        var caseCodeAccount = insertProviderAccount(orgId, "glm", "Case Variant Account");
        var caseMismatchBody = VERSION_BODY.formatted(projectId)
                .replace("\"providerCode\":\"GLM\",",
                        "\"providerCode\":\"GLM\",\"providerAccountId\":\""
                                + caseCodeAccount + "\",");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "bad-account")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-account3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseMismatchBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createVersionIdempotentReplayAndSameKeyDifferentBody() throws Exception {
        var first = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "replay-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var replay = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "replay-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);

        var changed = VERSION_BODY.formatted(projectId).replace("key-abc-123", "key-xyz-999");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "replay-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void listsRulesOrderedByKeyAscendingVersionDescending() throws Exception {
        insertRuleFull(orgId, actorMemberId, "glm-key", 1, "GLM", null,
                "PROVIDER_PROJECT", "p1", 10, projectId, null, null,
                "2020-01-01 00:00:00.000000", null, "ACTIVE");
        insertRuleFull(orgId, actorMemberId, "glm-key", 2, "GLM", null,
                "PROVIDER_PROJECT", "p1", 10, projectId, null, null,
                "2020-01-01 00:00:00.000000", null, "ACTIVE");
        insertRuleFull(orgId, actorMemberId, "alpha-key", 1, "GLM", null,
                "PROVIDER_USER", "u1", 5, null, costCenterId, null,
                "2020-01-01 00:00:00.000000", null, "ARCHIVED");

        mockMvc.perform(get("/api/v1/allocation-rules")
                        .header("Authorization", bearer())
                        .queryParam("page", "0").queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].ruleKey").value("alpha-key"))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[1].ruleKey").value("glm-key"))
                .andExpect(jsonPath("$.items[1].version").value(2))
                .andExpect(jsonPath("$.items[2].ruleKey").value("glm-key"))
                .andExpect(jsonPath("$.items[2].version").value(1));
    }

    @Test
    void getRuleExposesImmutableVersionAndCrossOrgIsNotFound() throws Exception {
        var ruleId = insertRuleFull(orgId, actorMemberId, "detail-key", 3, "GLM", null,
                "PROVIDER_PROJECT", "p1", 10, projectId, null, null,
                "2020-01-01 00:00:00.000000", null, "ACTIVE");

        mockMvc.perform(get("/api/v1/allocation-rules/{ruleId}", ruleId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(ruleId)))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.ruleKey").value("detail-key"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        var foreignMember = insertMember(foreignOrgId,
                insertUser("alloc-foreign-" + System.nanoTime() + "@example.com"));
        var foreignRule = insertRuleFull(foreignOrgId, foreignMember, "foreign-key", 1, "GLM",
                null, "PROVIDER_PROJECT", "fp", 1,
                insertTarget("project", foreignOrgId, "fp-" + System.nanoTime()), null, null,
                "2020-01-01 00:00:00.000000", null, "ACTIVE");

        mockMvc.perform(get("/api/v1/allocation-rules/{ruleId}", foreignRule)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void archiveTransitionsActiveToArchivedAndReplays() throws Exception {
        var ruleId = insertRuleFull(orgId, actorMemberId, "archive-key", 1, "GLM", null,
                "PROVIDER_PROJECT", "p1", 10, projectId, null, null,
                "2020-01-01 00:00:00.000000", null, "ACTIVE");

        var first = mockMvc.perform(post("/api/v1/allocation-rules/{ruleId}/archive", ruleId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "archive-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.id").value(Long.toString(ruleId)))
                .andReturn().getResponse().getContentAsString();

        var replay = mockMvc.perform(post("/api/v1/allocation-rules/{ruleId}/archive", ruleId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "archive-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);

        // A new key against the already-archived rule is a controlled conflict.
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleId}/archive", ruleId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "archive-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(get("/api/v1/allocation-rules/{ruleId}", ruleId)
                        .header("Authorization", bearer()))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void ruleVersionPublishAndArchiveRecordSecretSafeAudit() throws Exception {
        var createdBody = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "audit-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-audit-v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var ruleId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.<String>read(createdBody, "$.id"));

        mockMvc.perform(post("/api/v1/allocation-rules/{ruleId}/archive", ruleId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-audit-arch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        assertThat(auditRows(ruleId))
                .extracting(AuditRow::eventType)
                .containsExactly(
                        "ALLOCATION_RULE_VERSION_PUBLISHED",
                        "ALLOCATION_RULE_ARCHIVED");
        var auditMetadata = auditRows(ruleId).stream().map(AuditRow::metadataJson).toList();
        assertThat(auditMetadata)
                .noneMatch(json -> json.contains("key-abc-123"))
                .noneMatch(json -> json.contains("secret"));
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE org_id=? AND actor_user_id=?
                          AND subject_type='ALLOCATION_RULE' AND subject_id=?
                        """, Integer.class, orgId, actorUserId, ruleId))
                .isEqualTo(2);
    }

    private record AuditRow(String eventType, String metadataJson) {
    }

    private List<AuditRow> auditRows(long subjectId) {
        return jdbc.query("""
                SELECT event_type, metadata_json FROM audit_event
                WHERE subject_type='ALLOCATION_RULE' AND subject_id=?
                ORDER BY id
                """, (rs, rowNum) -> new AuditRow(
                rs.getString("event_type"), rs.getString("metadata_json")), subjectId);
    }

    @Test
    void ruleManagePermissionIsRequired() throws Exception {
        revokeAllAssignments();
        createPermissionRole("ALLOC_EDITOR", List.of(
                "COST_READ", "ALLOCATION_READ", "ALLOCATION_EDIT", "ALLOCATION_CONFIRM"));
        assign("ALLOC_EDITOR", "ORG", orgId);

        mockMvc.perform(get("/api/v1/allocation-rules")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "no-perm")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-noperm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void createVersionRejectsOverlappingActiveRangeOfSameKey() throws Exception {
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "overlap-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-ov1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId).replace(
                                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                                        + "\"effectiveTo\":\"2026-06-01T00:00:00Z\"")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "overlap-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-ov2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId).replace(
                                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                                "\"effectiveFrom\":\"2026-03-01T00:00:00Z\","
                                        + "\"effectiveTo\":\"2026-09-01T00:00:00Z\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM allocation_rule
                        WHERE org_id=? AND rule_key='overlap-key'
                        """, Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void createVersionAllowsAdjacentHalfOpenRangesOfSameKey() throws Exception {
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "adjacent-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-adj1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId).replace(
                                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\","
                                        + "\"effectiveTo\":\"2026-02-01T00:00:00Z\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // [a,b) then [b,c): adjacency is not overlap for half-open ranges.
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "adjacent-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-adj2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId).replace(
                                "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"",
                                "\"effectiveFrom\":\"2026-02-01T00:00:00Z\","
                                        + "\"effectiveTo\":\"2026-03-01T00:00:00Z\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void createVersionAllowsOverlapWithArchivedVersionOfSameKey() throws Exception {
        var archivedId = insertRuleFull(orgId, actorMemberId, "archived-overlap", 1, "GLM", null,
                "PROVIDER_API_KEY", "key-abc-123", 10, projectId, null, null,
                "2020-01-01 00:00:00.000000", null, "ACTIVE");
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleId}/archive", archivedId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-arch-ov"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "archived-overlap")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-arch-ov2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void createVersionReplaySurvivesTargetArchival() throws Exception {
        var first = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions",
                        "replay-target-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay-arch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The exact command is retried after its target died: the stored
        // response must replay with the first command's status and body
        // instead of being re-validated against current database state.
        deactivateTarget("project", orgId, projectId);
        var replay = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions",
                        "replay-target-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay-arch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void createVersionReplaySurvivesProviderAccountRemoval() throws Exception {
        var account = insertProviderAccount(orgId, "GLM", "Replay Account");
        var body = VERSION_BODY.formatted(projectId).replace(
                "\"providerCode\":\"GLM\",",
                "\"providerCode\":\"GLM\",\"providerAccountId\":\"" + account + "\",");
        var first = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions",
                        "replay-account-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay-acct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        jdbc.update("UPDATE provider_account SET provider_code='OPENAI' WHERE id=?", account);
        var replay = mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions",
                        "replay-account-key")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay-acct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void createVersionReplayOfFailedCommandIsNotInvented() throws Exception {
        // A first command that never completed (target already dead) must not
        // be replayable as success: it still fails validation.
        deactivateTarget("project", orgId, projectId);
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "replay-dead")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay-dead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "replay-dead")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "rule-replay-dead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void mutatingCommandsRequireIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/allocation-rules/{ruleKey}/versions", "no-key")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VERSION_BODY.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));

        mockMvc.perform(post("/api/v1/allocation-rules/{ruleId}/archive", 1L)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
    }
}
