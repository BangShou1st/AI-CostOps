package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deterministic rule proposal: exact hint matching, account/provider
 * constraints, the frozen tie-break, effective ranges, manual/confirmed
 * suppression, reuse and supersede, and idempotent replay.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class AllocationProposalApiIntegrationTest extends AllocationApiTestSupport {

    private static final String FROM_2020 = "2020-01-01 00:00:00.000000";
    private static final String UNTIL_2027 = "2027-12-31 00:00:00.000000";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void providerApiKeyExactMatchCreatesRuleDraftWithFullAmountLine() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_API_KEY", "key-abc-123");
        var ruleId = insertRuleFull(orgId, actorMemberId, "api-key-rule", 1, "GLM", null,
                "PROVIDER_API_KEY", "key-abc-123", 10, projectId, null, null,
                FROM_2020, UNTIL_2027, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.id").isString())
                .andExpect(jsonPath("$.decision.source").value("RULE"))
                .andExpect(jsonPath("$.decision.status").value("DRAFT"))
                .andExpect(jsonPath("$.decision.allocationRule.id").value(Long.toString(ruleId)))
                .andExpect(jsonPath("$.decision.allocationRule.version").value(1))
                .andExpect(jsonPath("$.decision.lines.length()").value(1))
                .andExpect(jsonPath("$.decision.lines[0].allocatedAmount").value("10.00000000"))
                .andExpect(jsonPath("$.decision.lines[0].projectId").value(Long.toString(projectId)))
                .andExpect(jsonPath("$.ruleTrace.id").value(Long.toString(ruleId)))
                .andExpect(jsonPath("$.ruleTrace.ruleKey").value("api-key-rule"))
                .andExpect(jsonPath("$.ruleTrace.version").value(1))
                .andExpect(jsonPath("$.ruleTrace.priority").value(10));

        // proposal never confirms nor touches the pointer
        assertThat(currentDecisionPointer(chargeId)).isNull();
        assertThat(decisionStatus(decisionIdOf(chargeId))).isEqualTo("DRAFT");
    }

    @Test
    void providerProjectAndUserExactMatch() throws Exception {
        var projectCharge = insertCharge("5.00000000");
        insertHintForCharge(orgId, projectCharge, "PROVIDER_PROJECT", "platinum-project");
        insertRuleFull(orgId, actorMemberId, "project-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "platinum-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", projectCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.source").value("RULE"));

        var userCharge = insertCharge("7.00000000");
        insertHintForCharge(orgId, userCharge, "PROVIDER_USER", "alice@example.com");
        insertRuleFull(orgId, actorMemberId, "user-rule", 1, "GLM", null,
                "PROVIDER_USER", "alice@example.com", 1, null, costCenterId, null,
                FROM_2020, null, "ACTIVE");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", userCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.lines[0].costCenterId")
                        .value(Long.toString(costCenterId)));
    }

    @Test
    void evaluatorMatchesOnlyExactCaseAndWhitespaceVariants() throws Exception {
        // Different rule keys, same priority and effective range: only the rule
        // whose stored matchValue is byte-for-byte equal to the hint may match.
        // The non-exact keys sort before "m-exact" so a collation-insensitive
        // comparison would pick them and fail the assertion.
        insertRuleFull(orgId, actorMemberId, "m-exact", 1, "GLM", null,
                "PROVIDER_API_KEY", "abc", 10, projectId, null, null,
                FROM_2020, UNTIL_2027, "ACTIVE");
        insertRuleFull(orgId, actorMemberId, "a-upper", 1, "GLM", null,
                "PROVIDER_API_KEY", "ABC", 10, projectId, null, null,
                FROM_2020, UNTIL_2027, "ACTIVE");
        insertRuleFull(orgId, actorMemberId, "b-trailing", 1, "GLM", null,
                "PROVIDER_API_KEY", "abc ", 10, projectId, null, null,
                FROM_2020, UNTIL_2027, "ACTIVE");
        insertRuleFull(orgId, actorMemberId, "c-leading", 1, "GLM", null,
                "PROVIDER_API_KEY", " abc", 10, projectId, null, null,
                FROM_2020, UNTIL_2027, "ACTIVE");

        var exactCharge = insertCharge("1.00000000");
        insertHintForCharge(orgId, exactCharge, "PROVIDER_API_KEY", "abc");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        exactCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-exact-lower"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.allocationRule.ruleKey").value("m-exact"));

        var upperCharge = insertCharge("2.00000000");
        insertHintForCharge(orgId, upperCharge, "PROVIDER_API_KEY", "ABC");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        upperCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-exact-upper"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.allocationRule.ruleKey").value("a-upper"));

        var trailingCharge = insertCharge("3.00000000");
        insertHintForCharge(orgId, trailingCharge, "PROVIDER_API_KEY", "abc ");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        trailingCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-exact-trailing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.allocationRule.ruleKey").value("b-trailing"));

        var leadingCharge = insertCharge("4.00000000");
        insertHintForCharge(orgId, leadingCharge, "PROVIDER_API_KEY", " abc");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        leadingCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-exact-leading"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.decision.allocationRule.ruleKey").value("c-leading"));
    }

    @Test
    void providerCodeCaseMismatchYieldsNoRuleMatch() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_API_KEY", "key-abc-123");
        // Everything matches except the rule's provider code letter case: the
        // evaluator comparison is exact, so 'glm' must not match the GLM charge.
        insertRuleFull(orgId, actorMemberId, "case-key", 1, "glm", null,
                "PROVIDER_API_KEY", "key-abc-123", 10, projectId, null, null,
                FROM_2020, UNTIL_2027, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-case-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_RULE_MATCH"))
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void providerAccountConstraintMatchesOnlyTheSameAccount() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "account-project");
        insertRuleFull(orgId, actorMemberId, "account-rule", 1, "GLM", accountId,
                "PROVIDER_PROJECT", "account-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-account-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));

        // a second account in the same org: the constrained rule must not match
        var otherAccount = insertProviderAccount(orgId, "GLM", "Second GLM Account");
        var otherCharge = insertCharge("3.00000000");
        insertHintForCharge(orgId, otherCharge, "PROVIDER_PROJECT", "account-project");
        jdbc.update("UPDATE import_batch SET provider_account_id=? WHERE org_id=?", otherAccount, orgId);
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", otherCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-account-no"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_RULE_MATCH"))
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void wrongProviderCodeNeverMatches() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "openai-project");
        insertRuleFull(orgId, actorMemberId, "openai-rule", 1, "OPENAI", null,
                "PROVIDER_PROJECT", "openai-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_RULE_MATCH"));
    }

    @Test
    void tieBreakPriorityThenRuleKeyThenVersionThenId() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "tie-project");

        // priorities 5 and 1: lower priority wins regardless of insertion order
        insertRuleFull(orgId, actorMemberId, "z-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "tie-project", 5, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        var winnerPriority = insertRuleFull(orgId, actorMemberId, "a-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "tie-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-tie-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.ruleTrace.id")
                        .value(Long.toString(winnerPriority)));

        // same priority, same key: higher version wins; same version: lower id wins
        var charge2 = insertCharge("10.00000000");
        insertHintForCharge(orgId, charge2, "PROVIDER_PROJECT", "tie-project-2");
        insertRuleFull(orgId, actorMemberId, "version-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "tie-project-2", 5, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        var winnerVersion = insertRuleFull(orgId, actorMemberId, "version-rule", 2, "GLM", null,
                "PROVIDER_PROJECT", "tie-project-2", 5, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", charge2)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-tie-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleTrace.id")
                        .value(Long.toString(winnerVersion)));
    }

    @Test
    void inactiveRulesAndEffectiveRangesAreHonored() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "range-project");

        // archived rule must never match
        insertRuleFull(orgId, actorMemberId, "archived-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "range-project", 1, projectId, null, null,
                FROM_2020, null, "ARCHIVED");
        // active but not yet effective (charge periodStart 2026-01-01)
        insertRuleFull(orgId, actorMemberId, "future-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "range-project", 1, projectId, null, null,
                "2027-01-01 00:00:00.000000", null, "ACTIVE");
        // effectiveTo equals the charge periodStart: boundary excluded
        insertRuleFull(orgId, actorMemberId, "boundary-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "range-project", 1, projectId, null, null,
                FROM_2020, JAN_1, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-range"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_RULE_MATCH"));

        // null effectiveTo and a covering range work
        insertRuleFull(orgId, actorMemberId, "covering-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "range-project", 2, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-range-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.ruleTrace.ruleKey").value("covering-rule"));
    }

    @Test
    void nullPeriodStartYieldsNoEffectiveTime() throws Exception {
        var chargeId = insertChargeWithoutPeriod(orgId, rawRecordId, "10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "no-period");
        insertRuleFull(orgId, actorMemberId, "no-period-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "no-period", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-noperiod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_EFFECTIVE_TIME"))
                .andExpect(jsonPath("$.ruleTrace").doesNotExist());
    }

    @Test
    void archivedTargetDisqualifiesTheWinningRule() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "dead-target");
        insertRuleFull(orgId, actorMemberId, "dead-target-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "dead-target", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        deactivateTarget("project", orgId, projectId);

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-deadtarget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_RULE_MATCH"));
    }

    @Test
    void sameWinningRuleProposalIsReused() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "reuse-project");
        insertRuleFull(orgId, actorMemberId, "reuse-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "reuse-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        var first = mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-reuse-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        var firstDecisionId = decisionIdFrom(first);

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-reuse-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REUSED"))
                .andExpect(jsonPath("$.decision.id").value(Long.toString(firstDecisionId)));

        assertThat(decisionCount(chargeId)).isEqualTo(1);
    }

    @Test
    void changedWinningRuleSupersedesOldRuleDraftPreservingLines() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "change-project");
        var oldRule = insertRuleFull(orgId, actorMemberId, "old-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "change-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-change-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
        var oldDraft = decisionIdOf(chargeId);

        // higher priority wins now; the old draft must be superseded with lines kept
        insertRuleFull(orgId, actorMemberId, "new-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "change-project", 1, null, costCenterId, null,
                FROM_2020, null, "ACTIVE");
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-change-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.ruleTrace.priority").value(1))
                .andExpect(jsonPath("$.ruleTrace.ruleKey").value("new-rule"));

        assertThat(decisionStatus(oldDraft)).isEqualTo("SUPERSEDED");
        assertThat(lineCount(oldDraft)).isEqualTo(1);
        assertThat(lineSum(oldDraft)).isEqualTo("10.00000000");
        assertThat(decisionCount(chargeId)).isEqualTo(2);
    }

    @Test
    void manualDraftAndConfirmedAllocationSuppressProposal() throws Exception {
        var manualCharge = insertCharge("10.00000000");
        insertHintForCharge(orgId, manualCharge, "PROVIDER_PROJECT", "manual-project");
        insertRuleFull(orgId, actorMemberId, "manual-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "manual-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        postDraft(manualCharge, "proposal-manual-draft");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", manualCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-manual"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANUAL_ALLOCATION_DRAFT_EXISTS"));

        var confirmedCharge = insertCharge("10.00000000");
        insertHintForCharge(orgId, confirmedCharge, "PROVIDER_PROJECT", "confirmed-project");
        insertRuleFull(orgId, actorMemberId, "confirmed-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "confirmed-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");
        var draft = decisionIdFrom(postDraft(confirmedCharge, "proposal-confirmed-draft"));
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", draft)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-confirm"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        confirmedCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-confirmed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_ALREADY_CONFIRMED"));
    }

    @Test
    void crossOrgRuleNeverMatchesAndForeignChargeIsNotFound() throws Exception {
        var foreignMember = insertMember(foreignOrgId,
                insertUser("alloc-foreign-" + System.nanoTime() + "@example.com"));
        insertRuleFull(foreignOrgId, foreignMember, "foreign-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "same-value", 1,
                insertTarget("project", foreignOrgId, "fp-" + System.nanoTime()), null, null,
                FROM_2020, null, "ACTIVE");

        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "same-value");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-crossrule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"));

        var foreignRaw = insertConfirmedRawRecord(foreignOrgId, foreignMember,
                insertProviderAccount(foreignOrgId, "GLM"), "foreign-" + System.nanoTime());
        var foreignCharge = insertCharge(foreignOrgId, foreignRaw, "7.00000000", "CNY", "CLEAN",
                JAN_1, FEB_1);
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        foreignCharge)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void employeeSelectionHintNeverMatches() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "EMPLOYEE_SELECTION", "manual-choice");
        insertRuleFull(orgId, actorMemberId, "employee-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "manual-choice", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-employee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_RULE_MATCH"));
    }

    @Test
    void proposalIsIdempotentAndRequiresEditPermission() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "idem-project");
        insertRuleFull(orgId, actorMemberId, "idem-rule", 1, "GLM", null,
                "PROVIDER_PROJECT", "idem-project", 1, projectId, null, null,
                FROM_2020, null, "ACTIVE");

        var first = mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-idem"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var replay = mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-idem"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(decisionCount(chargeId)).isEqualTo(1);

        revokeAllAssignments();
        createPermissionRole("ALLOC_READER", List.of("COST_READ", "ALLOCATION_READ"));
        assign("ALLOC_READER", "ORG", orgId);
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-noperm"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void noMatchResultIsAlsoIdempotent() throws Exception {
        var chargeId = insertCharge("10.00000000");
        insertHintForCharge(orgId, chargeId, "PROVIDER_PROJECT", "no-rule-at-all");

        var first = mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-nomatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andReturn().getResponse().getContentAsString();
        var replay = mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/allocation-proposal",
                        chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "proposal-nomatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(decisionCount(chargeId)).isZero();
    }

    // -- helpers ---------------------------------------------------------------

    private String postDraft(long chargeId, String key) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[
                                  {"allocatedAmount":"4.00000000","currency":"CNY","projectId":"%d"},
                                  {"allocatedAmount":"6.00000000","currency":"CNY","costCenterId":"%d"}]}
                                """.formatted(projectId, costCenterId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long decisionIdOf(long chargeId) {
        return jdbc.queryForObject("""
                SELECT id FROM allocation_decision
                WHERE org_id=? AND charge_fact_id=? ORDER BY id ASC LIMIT 1
                """, Long.class, orgId, chargeId);
    }

    private long decisionIdFrom(String response) {
        var start = response.indexOf("\"id\":\"") + "\"id\":\"".length();
        var end = response.indexOf('"', start);
        return Long.parseLong(response.substring(start, end));
    }

    private int decisionCount(long chargeId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Integer.class, orgId, chargeId);
    }
}
