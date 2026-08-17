package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Manual draft / replace-lines / confirm HTTP contract: exact decimal money,
 * idempotency replay and conflict, state-conflict problem codes, audit
 * atomicity, privacy-preserving 404s, and permission failures.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class AllocationDecisionApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    // -- manual draft ----------------------------------------------------------

    @Test
    void createManualDraftCreatesDraftWithServerAssignedLineIndexes() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var body = """
                {"lines":[
                  {"allocatedAmount":"4.00000000","currency":"CNY","projectId":"%d"},
                  {"allocatedAmount":"6.00000000","currency":"CNY","costCenterId":"%d"}]}
                """.formatted(projectId, costCenterId);

        var response = mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.subjectType").value("CHARGE_FACT"))
                .andExpect(jsonPath("$.chargeFactId").value(Long.toString(chargeId)))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdByMemberId").value(Long.toString(actorMemberId)))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].lineIndex").value(0))
                .andExpect(jsonPath("$.lines[0].allocatedAmount").value("4.00000000"))
                .andExpect(jsonPath("$.lines[0].projectId").value(Long.toString(projectId)))
                .andExpect(jsonPath("$.lines[1].lineIndex").value(1))
                .andExpect(jsonPath("$.lines[1].allocatedAmount").value("6.00000000"))
                .andExpect(jsonPath("$.lines[1].costCenterId").value(Long.toString(costCenterId)))
                .andReturn().getResponse().getContentAsString();

        assertThat(lineSum(decisionIdFrom(response))).isEqualTo("10.00000000");
    }

    @Test
    void createManualDraftAllowsPartialSumWithoutRemainder() throws Exception {
        var chargeId = insertCharge("10.00000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-partial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"3.00000000","currency":"CNY","teamId":"%d"}]}
                                """.formatted(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void createManualDraftRejectsInvalidMoneyShape() throws Exception {
        var chargeId = insertCharge("10.00000000");
        // nine fractional digits
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-bad1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.000000009","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // non-numeric
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-bad2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"abc","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // missing amount
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-bad3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createManualDraftRejectsZeroOrTwoTargets() throws Exception {
        var chargeId = insertCharge("10.00000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-target0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.00000000","currency":"CNY"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-target2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.00000000","currency":"CNY",
                                  "projectId":"%d","teamId":"%d"}]}
                                """.formatted(projectId, teamId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createManualDraftRejectsCurrencyMismatchAndInactiveTarget() throws Exception {
        var chargeId = insertCharge("10.00000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-cur")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.00000000","currency":"USD","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        deactivateTarget("project", orgId, projectId);
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-inactive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createManualDraftRejectsEmptyLines() throws Exception {
        var chargeId = insertCharge("10.00000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-empty")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createManualDraftRejectsNullLineElements() throws Exception {
        var chargeId = insertCharge("10.00000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-null")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-null2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.00000000","currency":"CNY",
                                  "projectId":"%d"},null]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var draft = decisionIdFrom(postDraft(chargeId, "draft-null3"));
        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createManualDraftRejectsPrecisionOverflowAtParseTime() throws Exception {
        var chargeId = insertCharge("10.00000000");
        // 13 integer digits pass the exact-8dp syntax but overflow DECIMAL(20,8).
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-overflow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"1234567890123.12345678",
                                  "currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var draft = decisionIdFrom(postDraft(chargeId, "draft-overflow2"));
        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"1234567890123.12345678",
                                  "currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void createManualDraftAcceptsLargestExactDecimalMoney() throws Exception {
        var chargeId = insertCharge("999999999999.99999999");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-max")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"999999999999.99999999",
                                  "currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].allocatedAmount")
                        .value("999999999999.99999999"));
    }

    @Test
    void createManualDraftKeepsExactEightFractionalDigitContract() throws Exception {
        var chargeId = insertCharge("10.00000000");
        // Fewer fractional digits stay rejected: the API money contract is
        // exactly 8 fractional digits, not "at most 8".
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-shortdp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.50","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-nodp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void secondManualDraftCommandConflicts() throws Exception {
        var chargeId = insertCharge("10.00000000");
        postDraft(chargeId, "draft-2a");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-2b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANUAL_ALLOCATION_DRAFT_EXISTS"));
    }

    @Test
    void manualDraftSupersedesRuleDraftAndPreservesItsLines() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var ruleId = insertRule(orgId, actorMemberId, projectId, accountId,
                "PROVIDER_PROJECT", "legacy-project");
        var ruleDraft = insertRuleDraft(orgId, chargeId, ruleId, projectId,
                "10.00000000", "CNY");

        var response = postDraft(chargeId, "draft-override");
        var manualDraft = decisionIdFrom(response);

        assertThat(decisionStatus(ruleDraft)).isEqualTo("SUPERSEDED");
        assertThat(lineCount(ruleDraft)).isEqualTo(1);
        assertThat(decisionStatus(manualDraft)).isEqualTo("DRAFT");
        assertThat(currentDecisionPointer(chargeId)).isNull();
    }

    @Test
    void manualDraftOnConfirmedChargeConflicts() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = postDraft(chargeId, "draft-conf-a");
        confirm(decisionIdFrom(draft), "confirm-conf-a");

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-conf-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_ALREADY_CONFIRMED"));
    }

    @Test
    void manualDraftIdempotentReplayReturnsIdenticalBody() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var first = mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var replay = mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(decisionCount(chargeId)).isEqualTo(1);
    }

    @Test
    void manualDraftSameKeyDifferentBodyConflicts() throws Exception {
        var chargeId = insertCharge("10.00000000");
        postDraft(chargeId, "draft-key");

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"6.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void manualDraftOnForeignChargeIsNotFound() throws Exception {
        var foreignUser = insertUser("alloc-foreign-" + System.nanoTime() + "@example.com");
        var foreignMember = insertMember(foreignOrgId, foreignUser);
        var foreignRaw = insertConfirmedRawRecord(foreignOrgId, foreignMember,
                insertProviderAccount(foreignOrgId, "GLM"), "foreign-" + System.nanoTime());
        var foreignCharge = insertCharge(foreignOrgId, foreignRaw, "7.00000000", "CNY", "CLEAN",
                JAN_1, FEB_1);

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", foreignCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-foreign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"7.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void manualDraftWithoutEditPermissionIsForbidden() throws Exception {
        revokeAllAssignments();
        createPermissionRole("ALLOC_READER", java.util.List.of("COST_READ", "ALLOCATION_READ"));
        assign("ALLOC_READER", "ORG", orgId);
        var chargeId = insertCharge("10.00000000");

        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-noperm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // -- replace lines ---------------------------------------------------------

    @Test
    void replaceLinesReplacesTheWholeLineSetWithStableIndexes() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-edit-a"));

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","teamId":"%d"}]}
                                """.formatted(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].lineIndex").value(0))
                .andExpect(jsonPath("$.lines[0].allocatedAmount").value("10.00000000"))
                .andExpect(jsonPath("$.lines[0].teamId").value(Long.toString(teamId)));

        assertThat(lineCount(draft)).isEqualTo(1);
    }

    @Test
    void replaceLinesRejectsEmptyLines() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-edit-b"));

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void replaceLinesOnConfirmedDecisionConflicts() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-edit-c"));
        confirm(draft, "confirm-edit-c");

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_DRAFT"));
    }

    @Test
    void replaceLinesOnSupersededRuleDraftConflicts() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var ruleId = insertRule(orgId, actorMemberId, projectId, accountId,
                "PROVIDER_PROJECT", "legacy-project");
        var ruleDraft = insertRuleDraft(orgId, chargeId, ruleId, projectId,
                "10.00000000", "CNY");
        postDraft(chargeId, "draft-edit-d"); // supersedes the rule draft

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", ruleDraft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"10.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_DRAFT"));
    }

    @Test
    void replaceLinesOnRuleDraftConflictsWithoutConvertingIt() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var ruleId = insertRule(orgId, actorMemberId, projectId, accountId,
                "PROVIDER_PROJECT", "legacy-project");
        var ruleDraft = insertRuleDraft(orgId, chargeId, ruleId, projectId,
                "10.00000000", "CNY");

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", ruleDraft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"5.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_DRAFT"));

        assertThat(decisionStatus(ruleDraft)).isEqualTo("DRAFT");
        assertThat(lineSum(ruleDraft)).isEqualTo("10.00000000");
    }

    // -- confirm ---------------------------------------------------------------

    @Test
    void confirmExactSumSucceedsAndAudits() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-confirm-ok"));

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.toString(draft)))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.source").value("MANUAL"));

        assertThat(decisionStatus(draft)).isEqualTo("CONFIRMED");
        assertThat(currentDecisionPointer(chargeId)).isEqualTo(draft);
        assertThat(auditCount("ALLOCATION_DECISION_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void confirmNegativeAmountExactSucceeds() throws Exception {
        var chargeId = insertCharge("-1.25000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-neg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"-1.25000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk());

        var draft = latestDraftOf(chargeId);
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-neg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.lines[0].allocatedAmount").value("-1.25000000"));
    }

    @Test
    void confirmZeroAmountExactSucceeds() throws Exception {
        var chargeId = insertCharge("0.00000000");
        mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "draft-zero")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"0.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk());

        var draft = latestDraftOf(chargeId);
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-zero"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void confirmUnderAndOverAllocationConflict() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-sum"));

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"4.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-under"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_SUM_MISMATCH"));

        mockMvc.perform(put("/api/v1/allocation-decisions/{decisionId}/lines", draft)
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[{"allocatedAmount":"12.00000000","currency":"CNY","projectId":"%d"}]}
                                """.formatted(projectId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-over"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_SUM_MISMATCH"));
    }

    @Test
    void confirmRejectsLineCurrencyMismatch() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-cur-confirm"));
        // corrupt one line's currency behind the service's back
        jdbc.update("UPDATE allocation_line SET currency='USD' WHERE decision_id=?", draft);

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-cur"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(decisionStatus(draft)).isEqualTo("DRAFT");
    }

    @Test
    void confirmOnSuspectedDuplicateIsNotEligible() throws Exception {
        var chargeId = insertCharge(orgId, rawRecordId, "10.00000000", "CNY",
                "SUSPECTED_DUPLICATE", JAN_1, FEB_1);
        var draft = decisionIdFrom(postDraft(chargeId, "draft-susp"));
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-susp"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_NOT_ELIGIBLE"));
    }

    @Test
    void confirmOnExcludedChargeIsNotEligible() throws Exception {
        var chargeId = insertCharge(orgId, rawRecordId, "10.00000000", "CNY",
                "EXCLUDED_DUPLICATE", JAN_1, FEB_1);
        var draft = decisionIdFrom(postDraft(chargeId, "draft-excl"));
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-excl"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_NOT_ELIGIBLE"));
    }

    @Test
    void confirmOnUnconfirmedImportIsNotEligible() throws Exception {
        var unconfirmedRaw = insertUnconfirmedRawRecord(
                orgId, actorMemberId, accountId, "unconfirmed-" + System.nanoTime());
        var chargeId = insertCharge(orgId, unconfirmedRaw, "5.00000000", "CNY", "CLEAN", JAN_1, FEB_1);
        var draft = decisionIdFrom(postDraft(chargeId, "draft-unconf"));
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-unconf"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_NOT_ELIGIBLE"));
    }

    @Test
    void confirmRejectsWrongConfirmedAttemptLineage() throws Exception {
        var wrongRaw = insertWrongLineageRawRecord(
                orgId, actorMemberId, accountId, "wrong-lineage-" + System.nanoTime());
        var chargeId = insertCharge(orgId, wrongRaw, "5.00000000", "CNY", "CLEAN", JAN_1, FEB_1);
        var draft = decisionIdFrom(postDraft(chargeId, "draft-lineage"));
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-lineage"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_NOT_ELIGIBLE"));
    }

    @Test
    void confirmTwiceWithNewKeyConflicts() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-twice"));
        confirm(draft, "confirm-twice-a");

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-twice-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_DRAFT"));
    }

    @Test
    void competingConfirmedDecisionBlocksTheSecond() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var manual = decisionIdFrom(postDraft(chargeId, "draft-comp-a"));
        confirm(manual, "confirm-comp-a");

        var ruleId = insertRule(orgId, actorMemberId, projectId, accountId,
                "PROVIDER_PROJECT", "competing-rule");
        var ruleDraft = insertRuleDraft(orgId, chargeId, ruleId, projectId,
                "10.00000000", "CNY");

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", ruleDraft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-comp-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_ALREADY_CONFIRMED"));
    }

    @Test
    void confirmIdempotentReplayReturnsIdenticalBody() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-confirm-replay"));

        var first = mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-replay"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var replay = mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-replay"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(auditCount("ALLOCATION_DECISION_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void confirmRejectsInactiveTarget() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-target-confirm"));
        deactivateTarget("project", orgId, projectId);

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-target"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_NOT_ELIGIBLE"));
    }

    @Test
    void confirmOnForeignDecisionIsNotFound() throws Exception {
        var foreignUser = insertUser("alloc-foreign-" + System.nanoTime() + "@example.com");
        var foreignMember = insertMember(foreignOrgId, foreignUser);
        var foreignRaw = insertConfirmedRawRecord(foreignOrgId, foreignMember,
                insertProviderAccount(foreignOrgId, "GLM"), "foreign-" + System.nanoTime());
        var foreignCharge = insertCharge(foreignOrgId, foreignRaw, "7.00000000", "CNY", "CLEAN",
                JAN_1, FEB_1);
        var foreignProject = insertTarget("project", foreignOrgId, "fp-" + System.nanoTime());
        var foreignDraft = insertRuleDraft(foreignOrgId, foreignCharge,
                insertRule(foreignOrgId, foreignMember, foreignProject,
                        insertProviderAccount(foreignOrgId, "GLM"), "PROVIDER_PROJECT", "fp"),
                foreignProject, "7.00000000", "CNY");

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", foreignDraft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void confirmWithoutPermissionIsForbidden() throws Exception {
        revokeAllAssignments();
        createPermissionRole("ALLOC_EDITOR", java.util.List.of(
                "COST_READ", "ALLOCATION_READ", "ALLOCATION_EDIT"));
        assign("ALLOC_EDITOR", "ORG", orgId);
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-noconfirm"));

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "confirm-noperm"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void confirmWithoutIdempotencyKeyIsRejected() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var draft = decisionIdFrom(postDraft(chargeId, "draft-nokey"));

        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
    }

    // -- reads -----------------------------------------------------------------

    @Test
    void decisionReadsExposeRuleTraceAndLines() throws Exception {
        var chargeId = insertCharge("10.00000000");
        var ruleId = insertRule(orgId, actorMemberId, projectId, accountId,
                "PROVIDER_PROJECT", "trace-project");
        var ruleDraft = insertRuleDraft(orgId, chargeId, ruleId, projectId,
                "10.00000000", "CNY");

        mockMvc.perform(get("/api/v1/allocation-decisions/{decisionId}", ruleDraft)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("RULE"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.allocationRule.id").value(Long.toString(ruleId)))
                .andExpect(jsonPath("$.allocationRule.ruleKey").exists())
                .andExpect(jsonPath("$.allocationRule.version").value(1))
                .andExpect(jsonPath("$.allocationRule.priority").value(1))
                .andExpect(jsonPath("$.lines[0].allocatedAmount").value("10.00000000"));

        mockMvc.perform(get("/api/v1/costs/charges/{chargeFactId}/allocation-decisions", chargeId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(Long.toString(ruleDraft)))
                .andExpect(jsonPath("$[0].source").value("RULE"))
                .andExpect(jsonPath("$[0].allocationRule.ruleKey").exists());
    }

    @Test
    void decisionReadOnForeignDecisionIsNotFound() throws Exception {
        var foreignUser = insertUser("alloc-foreign-" + System.nanoTime() + "@example.com");
        var foreignMember = insertMember(foreignOrgId, foreignUser);
        var foreignRaw = insertConfirmedRawRecord(foreignOrgId, foreignMember,
                insertProviderAccount(foreignOrgId, "GLM"), "foreign-" + System.nanoTime());
        var foreignCharge = insertCharge(foreignOrgId, foreignRaw, "7.00000000", "CNY", "CLEAN",
                JAN_1, FEB_1);
        var foreignProject = insertTarget("project", foreignOrgId, "fp-" + System.nanoTime());
        var foreignDraft = insertRuleDraft(foreignOrgId, foreignCharge,
                insertRule(foreignOrgId, foreignMember, foreignProject,
                        insertProviderAccount(foreignOrgId, "GLM"), "PROVIDER_PROJECT", "fp"),
                foreignProject, "7.00000000", "CNY");

        mockMvc.perform(get("/api/v1/allocation-decisions/{decisionId}", foreignDraft)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // -- helpers ---------------------------------------------------------------

    private String postDraft(long chargeId, String key) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[
                                  {"allocatedAmount":"4.00000000","currency":"CNY","projectId":"%d"},
                                  {"allocatedAmount":"6.00000000","currency":"CNY","costCenterId":"%d"}]}
                                """.formatted(projectId, costCenterId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void confirm(long decisionId, String key) throws Exception {
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", decisionId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk());
    }

    private long decisionIdFrom(String response) {
        var start = response.indexOf("\"id\":\"") + "\"id\":\"".length();
        var end = response.indexOf('"', start);
        return Long.parseLong(response.substring(start, end));
    }

    private long latestDraftOf(long chargeId) {
        return jdbc.queryForObject("""
                SELECT id FROM allocation_decision
                WHERE org_id=? AND charge_fact_id=? AND status='DRAFT' ORDER BY id DESC LIMIT 1
                """, Long.class, orgId, chargeId);
    }

    private int decisionCount(long chargeId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Integer.class, orgId, chargeId);
    }
}
