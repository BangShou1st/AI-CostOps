package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V9 schema contract for the M3 Group 2 duplicate/attribution foundation:
 * duplicate_candidate, allocation_rule, allocation_decision, allocation_line,
 * the same-org composite duplicate pointer on charge_fact, and the composite
 * current-allocation-decision pointer.
 */
@SpringBootTest
@Tag("integration")
class M3Group2DuplicateAttributionSchemaIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> GROUP2_TABLES = Set.of(
            "duplicate_candidate", "allocation_rule", "allocation_decision", "allocation_line");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long fixtureCounter;

    @BeforeEach
    void setUp() {
        cleanGroup2();
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        cleanGroup2();
        M2DatabaseCleaner.clean(jdbcTemplate);
    }

    // Group 2 rows must be removed before M2DatabaseCleaner can delete charge_fact.
    private void cleanGroup2() {
        jdbcTemplate.update("DELETE FROM duplicate_candidate");
        jdbcTemplate.update("DELETE FROM allocation_line");
        jdbcTemplate.update(
                "UPDATE charge_fact SET current_allocation_decision_id=NULL, duplicate_of_charge_id=NULL");
        jdbcTemplate.update("DELETE FROM allocation_decision");
        jdbcTemplate.update("DELETE FROM allocation_rule");
    }

    @Test
    void migratesGroup2Tables() {
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class));

        assertThat(tables).containsAll(GROUP2_TABLES);
    }

    @Test
    void chargeFactDuplicatePointerUsesSameOrgCompositeForeignKey() {
        var mapping = jdbcTemplate.query("""
                SELECT column_name, referenced_column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE()
                  AND table_name = 'charge_fact'
                  AND constraint_name = 'fk_charge_fact_duplicate'
                """, (rs, rowNum) -> rs.getString("column_name") + " -> " + rs.getString("referenced_column_name"));

        assertThat(Set.copyOf(mapping)).containsExactlyInAnyOrder(
                "duplicate_of_charge_id -> id", "org_id -> org_id");
    }

    @Test
    void candidatePairMustBeOrderedLowToHigh() {
        var fixture = insertConfirmedCharge("cand-order");
        var low = insertCharge(fixture, 1, "10.00000000");
        var high = insertCharge(fixture, 2, "20.00000000");

        assertThatThrownBy(() -> insertCandidate(fixture.orgId(), high, low, "EXACT", "v1", "OPEN", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_duplicate_candidate_order");
    }

    @Test
    void candidatePairIsUniquePerAlgorithmVersion() {
        var fixture = insertConfirmedCharge("cand-uniq");
        var low = insertCharge(fixture, 1, "10.00000000");
        var high = insertCharge(fixture, 2, "20.00000000");

        insertCandidate(fixture.orgId(), low, high, "EXACT", "v1", "OPEN", null);

        assertThatThrownBy(() -> insertCandidate(fixture.orgId(), low, high, "OVERLAP", "v1", "OPEN", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_duplicate_candidate_pair_version");

        // A future algorithm version may open a new candidate for the same pair.
        insertCandidate(fixture.orgId(), low, high, "OVERLAP", "v2", "OPEN", null);
    }

    @Test
    void candidateStatusRequiresResolvedAtConsistency() {
        var fixture = insertConfirmedCharge("cand-resolve");
        var low = insertCharge(fixture, 1, "10.00000000");
        var high = insertCharge(fixture, 2, "20.00000000");

        insertCandidate(fixture.orgId(), low, high, "EXACT", "v1", "OPEN", null);
        insertCandidate(fixture.orgId(), low, high, "EXACT", "v3", "KEPT_CLEAN",
                "2026-08-16 00:00:00.000000");

        assertThatThrownBy(() -> insertCandidate(fixture.orgId(), low, high, "OVERLAP", "v4", "OPEN",
                "2026-08-16 00:00:00.000000"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_duplicate_candidate_resolution");
        assertThatThrownBy(() -> insertCandidate(fixture.orgId(), low, high, "OVERLAP", "v5",
                "KEPT_CLEAN", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_duplicate_candidate_resolution");
    }

    @Test
    void rejectsCrossOrganizationCandidatePair() {
        var orgA = insertConfirmedCharge("cand-cross-a");
        var orgB = insertConfirmedCharge("cand-cross-b");
        var chargeInB = insertCharge(orgB, 1, "10.00000000");

        assertThatThrownBy(() -> insertCandidate(orgB.orgId(), orgA.chargeId(), chargeInB, "EXACT", "v1",
                "OPEN", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_duplicate_candidate_charge_org");
    }

    @Test
    void allocationRuleKeyVersionIsUniquePerOrg() {
        var fixture = insertConfirmedCharge("rule-uniq");

        insertRule(fixture, "team-charge", 1, 10);
        assertThatThrownBy(() -> insertRule(fixture, "team-charge", 1, 11))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_allocation_rule_key_version");

        var otherOrg = insertConfirmedCharge("rule-uniq-other");
        insertRule(otherOrg, "team-charge", 1, 11);
    }

    @Test
    void allocationRuleVersionMustBePositive() {
        var fixture = insertConfirmedCharge("rule-version");

        assertThatThrownBy(() -> insertRule(fixture, "zero-version", 0, 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_version");
    }

    @Test
    void allocationRulePriorityStaysWithinBounds() {
        var fixture = insertConfirmedCharge("rule-priority");

        assertThatThrownBy(() -> insertRule(fixture, "low-priority", 1, 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_priority");
        assertThatThrownBy(() -> insertRule(fixture, "high-priority", 1, 10000))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_priority");
        insertRule(fixture, "edge-low", 1, 1);
        insertRule(fixture, "edge-high", 1, 9999);
    }

    @Test
    void allocationRuleHistoricalVersionsMaySharePriority() {
        var fixture = insertConfirmedCharge("rule-shared-priority");

        insertRule(fixture, "shared", 1, 10);
        insertRule(fixture, "shared", 2, 10);
        insertRule(fixture, "shared", 3, 10);
    }

    @Test
    void allocationRuleMatchTypeIsExplicitProviderHintEnum() {
        var fixture = insertConfirmedCharge("rule-hint");

        assertThatThrownBy(() -> insertRule(fixture, "dimension", 1, 10, "DIMENSION"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_match_hint_type");
        insertRule(fixture, "api-key", 1, 10, "PROVIDER_API_KEY");
        insertRule(fixture, "provider-project", 1, 11, "PROVIDER_PROJECT");
        insertRule(fixture, "provider-user", 1, 12, "PROVIDER_USER");
    }

    @Test
    void allocationRuleTargetMustBeExactlyOne() {
        var fixture = insertConfirmedCharge("rule-target");
        var costCenterId = insertCostCenter(fixture.orgId());

        assertThatThrownBy(() -> insertRuleWithoutTarget(fixture, "no-target", 1, 10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_target");
        assertThatThrownBy(() -> insertRuleWithTwoTargets(fixture, "two-targets", 1, 10, costCenterId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_target");
    }

    @Test
    void allocationRuleEffectiveRangeIsHalfOpenValidated() {
        var fixture = insertConfirmedCharge("rule-effective");

        assertThatThrownBy(() -> insertRule(fixture, "empty-range", 1, 10,
                "2026-01-01 00:00:00.000000", "2026-01-01 00:00:00.000000"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_rule_effective");
        insertRule(fixture, "open-ended", 1, 10, "2026-01-01 00:00:00.000000", null);
    }

    @Test
    void adjacentEffectiveRangesOfSameKeyMayCoexist() {
        var fixture = insertConfirmedCharge("rule-adjacent");

        insertRule(fixture, "adjacent", 1, 10, "2026-01-01 00:00:00.000000", "2026-02-01 00:00:00.000000");
        insertRule(fixture, "adjacent", 2, 10, "2026-02-01 00:00:00.000000", "2026-03-01 00:00:00.000000");
    }

    @Test
    void chargeSubjectRequiresChargeFactIdentity() {
        var fixture = insertConfirmedCharge("dec-charge");

        assertThatThrownBy(() -> insertDecision(fixture.orgId(), "CHARGE_FACT", null, null, "MANUAL", null, "DRAFT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_decision_subject");
        assertThatThrownBy(() -> insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), 99L,
                "MANUAL", null, "DRAFT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_decision_subject");
    }

    @Test
    void expenseSubjectKeepsIdentityWithForeignKey() {
        var fixture = insertConfirmedCharge("dec-expense");
        var expenseId = insertExpenseClaim(fixture.orgId(), fixture.memberId());

        assertThatThrownBy(() -> insertDecision(fixture.orgId(), "EXPENSE_CLAIM", null, null, "MANUAL", null, "DRAFT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_decision_subject");

        insertDecision(fixture.orgId(), "EXPENSE_CLAIM", null, expenseId, "MANUAL", null, "DRAFT");

        var expenseReferences = jdbcTemplate.queryForList("""
                SELECT referenced_table_name FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND table_name = 'allocation_decision'
                  AND column_name = 'expense_claim_id' AND referenced_table_name IS NOT NULL
                """, String.class);
        assertThat(expenseReferences).isNotEmpty();
    }

    @Test
    void ruleSourceDecisionRequiresRuleTrace() {
        var fixture = insertConfirmedCharge("dec-rule");

        assertThatThrownBy(() -> insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "RULE", null, "DRAFT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_decision_source_rule");
    }

    @Test
    void manualSourceDecisionForbidsRuleTrace() {
        var fixture = insertConfirmedCharge("dec-manual");
        var ruleId = insertRule(fixture, "manual-trace", 1, 10);

        assertThatThrownBy(() -> insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "MANUAL", ruleId, "DRAFT"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_decision_source_rule");
    }

    @Test
    void singleConfirmedDecisionPerCharge() {
        var fixture = insertConfirmedCharge("dec-confirmed");

        insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null, "MANUAL", null, "CONFIRMED");

        assertThatThrownBy(() -> insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "MANUAL", null, "CONFIRMED"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_allocation_decision_confirmed_charge");

        // Drafts never collide with the confirmed decision of the same charge.
        insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null, "MANUAL", null, "DRAFT");
    }

    @Test
    void currentDecisionPointerMustReferenceSameChargeDecision() {
        var fixture = insertConfirmedCharge("dec-pointer");
        var decisionForCharge = insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "MANUAL", null, "DRAFT");

        jdbcTemplate.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionForCharge, fixture.chargeId());

        var otherCharge = insertCharge(fixture, 1, "30.00000000");
        var decisionForOther = insertDecision(fixture.orgId(), "CHARGE_FACT", otherCharge, null,
                "MANUAL", null, "DRAFT");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionForOther, fixture.chargeId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_charge_fact_current_allocation_decision");
    }

    @Test
    void currentDecisionPointerStaysInsideOrganization() {
        var orgA = insertConfirmedCharge("dec-cross-org-a");
        var orgB = insertConfirmedCharge("dec-cross-org-b");
        var chargeInB = insertCharge(orgB, 1, "10.00000000");
        var foreignDecision = insertDecision(orgB.orgId(), "CHARGE_FACT", chargeInB, null, "MANUAL", null, "DRAFT");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                foreignDecision, orgA.chargeId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_charge_fact_current_allocation_decision");
    }

    @Test
    void allocationLineIndexMustBeNonNegative() {
        var fixture = insertConfirmedCharge("line-index");
        var decisionId = insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "MANUAL", null, "DRAFT");

        assertThatThrownBy(() -> insertLine(fixture.orgId(), decisionId, -1, fixture.projectId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_line_index");
    }

    @Test
    void allocationLineTargetMustBeExactlyOne() {
        var fixture = insertConfirmedCharge("line-target");
        var decisionId = insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "MANUAL", null, "DRAFT");

        assertThatThrownBy(() -> insertLineWithoutTarget(fixture.orgId(), decisionId, 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_line_target");
    }

    @Test
    void allocationLineIndexUniquePerDecision() {
        var fixture = insertConfirmedCharge("line-uniq");
        var decisionId = insertDecision(fixture.orgId(), "CHARGE_FACT", fixture.chargeId(), null,
                "MANUAL", null, "DRAFT");

        insertLine(fixture.orgId(), decisionId, 0, fixture.projectId());
        assertThatThrownBy(() -> insertLine(fixture.orgId(), decisionId, 0, fixture.projectId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_allocation_line_decision_index");
    }

    @Test
    void allocationLineOrganizationMatchesDecisionOrganization() {
        var orgA = insertConfirmedCharge("line-org-a");
        var orgB = insertConfirmedCharge("line-org-b");
        var decisionInA = insertDecision(orgA.orgId(), "CHARGE_FACT", orgA.chargeId(), null, "MANUAL", null, "DRAFT");

        assertThatThrownBy(() -> insertLine(orgB.orgId(), decisionInA, 0, orgB.projectId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_allocation_line_decision_org");
    }

    // -- fixtures ----------------------------------------------------------------

    private record OrgFixture(long orgId, long memberId, long rawRecordId, long chargeId, long projectId) {
    }

    private long insertExpenseClaim(long orgId, long claimantMemberId) {
        jdbcTemplate.update("""
                INSERT INTO expense_claim(
                    org_id,claimant_member_id,evidence_id,expense_date,amount,currency,status,
                    current_allocation_decision_id,approval_case_id,version,created_at,updated_at)
                VALUES (?,?,NULL,'2026-08-01','10.00000000','CNY','APPROVED',
                    NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, claimantMemberId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM expense_claim WHERE org_id=? AND claimant_member_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId, claimantMemberId);
    }

    /** Creates one confirmed-import lineage with one CLEAN charge (fact index 0). */
    private OrgFixture insertConfirmedCharge(String label) {
        var suffix = label + "-" + ++fixtureCounter + "-" + System.nanoTime();
        var slug = ("g2-" + suffix).substring(0, Math.min(63, "g2-".length() + suffix.length()));
        var orgId = insertOrganization("Group2 " + label, slug);
        var userId = insertUser(suffix + "@example.com");
        var memberId = insertMember(orgId, userId);
        var projectId = insertProject(orgId);
        var rawRecordId = insertRawRecordUnderConfirmedBatch(orgId, memberId, suffix);
        var fixture = new OrgFixture(orgId, memberId, rawRecordId, 0, projectId);
        var chargeId = insertCharge(fixture, 0, "10.00000000");
        return new OrgFixture(orgId, memberId, rawRecordId, chargeId, projectId);
    }

    private long insertCharge(OrgFixture fixture, int factIndex, String amount) {
        jdbcTemplate.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,created_at)
                VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, fixture.orgId(), fixture.rawRecordId(), factIndex, "GLM", "USAGE", amount, "CNY");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM charge_fact WHERE raw_record_id=? AND fact_index=?",
                Long.class, fixture.rawRecordId(), factIndex);
    }

    private void insertCandidate(long orgId, long lowChargeId, long highChargeId, String type,
            String algorithmVersion, String status, String resolvedAt) {
        jdbcTemplate.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                    match_reason,status,created_at,resolved_at)
                VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?)
                """, orgId, lowChargeId, highChargeId, type, fingerprintOf(type, algorithmVersion),
                algorithmVersion, "evidence", status, resolvedAt);
    }

    private static String fingerprintOf(String type, String algorithmVersion) {
        return (type + "-" + algorithmVersion + "-").repeat(8).substring(0, 64);
    }

    private long insertRule(OrgFixture fixture, String ruleKey, int version, int priority) {
        return insertRule(fixture, ruleKey, version, priority, "PROVIDER_USER",
                "2026-01-01 00:00:00.000000", null);
    }

    private long insertRule(OrgFixture fixture, String ruleKey, int version, int priority, String matchHintType) {
        return insertRule(fixture, ruleKey, version, priority, matchHintType,
                "2026-01-01 00:00:00.000000", null);
    }

    private long insertRule(OrgFixture fixture, String ruleKey, int version, int priority,
            String effectiveFrom, String effectiveTo) {
        return insertRule(fixture, ruleKey, version, priority, "PROVIDER_USER", effectiveFrom, effectiveTo);
    }

    private long insertRule(OrgFixture fixture, String ruleKey, int version, int priority, String matchHintType,
            String effectiveFrom, String effectiveTo) {
        jdbcTemplate.update("""
                INSERT INTO allocation_rule(
                    org_id,rule_key,version,name,provider_code,match_hint_type,match_value,priority,
                    target_project_id,effective_from,effective_to,created_by_member_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, fixture.orgId(), ruleKey, version, "Rule " + ruleKey, "GLM", matchHintType, "match",
                priority, fixture.projectId(), effectiveFrom, effectiveTo, fixture.memberId());
        return jdbcTemplate.queryForObject(
                "SELECT id FROM allocation_rule WHERE org_id=? AND rule_key=? AND version=?",
                Long.class, fixture.orgId(), ruleKey, version);
    }

    private void insertRuleWithoutTarget(OrgFixture fixture, String ruleKey, int version, int priority) {
        jdbcTemplate.update("""
                INSERT INTO allocation_rule(
                    org_id,rule_key,version,name,provider_code,match_hint_type,match_value,priority,
                    effective_from,created_by_member_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, fixture.orgId(), ruleKey, version, "Rule " + ruleKey, "GLM", "PROVIDER_USER", "match",
                priority, "2026-01-01 00:00:00.000000", fixture.memberId());
    }

    private void insertRuleWithTwoTargets(OrgFixture fixture, String ruleKey, int version, int priority,
            long costCenterId) {
        jdbcTemplate.update("""
                INSERT INTO allocation_rule(
                    org_id,rule_key,version,name,provider_code,match_hint_type,match_value,priority,
                    target_project_id,target_cost_center_id,effective_from,created_by_member_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, fixture.orgId(), ruleKey, version, "Rule " + ruleKey, "GLM", "PROVIDER_USER", "match",
                priority, fixture.projectId(), costCenterId, "2026-01-01 00:00:00.000000", fixture.memberId());
    }

    private long insertDecision(long orgId, String subjectType, Long chargeFactId, Long expenseClaimId,
            String source, Long ruleId, String status) {
        jdbcTemplate.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,allocation_rule_id,
                    status,created_at)
                VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, subjectType, chargeFactId, expenseClaimId, source, ruleId, status);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=?", Long.class, orgId);
    }

    private void insertLine(long orgId, long decisionId, int lineIndex, long projectId) {
        jdbcTemplate.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,project_id,created_at)
                VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, decisionId, lineIndex, "10.00000000", "CNY", projectId);
    }

    private void insertLineWithoutTarget(long orgId, long decisionId, int lineIndex) {
        jdbcTemplate.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, decisionId, lineIndex, "10.00000000", "CNY");
    }

    private long insertCostCenter(long orgId) {
        jdbcTemplate.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Group2 Cost Center','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "CC-" + orgId);
        return jdbcTemplate.queryForObject("SELECT id FROM cost_center WHERE org_id=? AND code=?",
                Long.class, orgId, "CC-" + orgId);
    }

    private long insertProject(long orgId) {
        jdbcTemplate.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Group2 Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "P-" + orgId);
        return jdbcTemplate.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?",
                Long.class, orgId, "P-" + orgId);
    }

    private long insertRawRecordUnderConfirmedBatch(long orgId, long memberId, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbcTemplate.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, "usage.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbcTemplate.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbcTemplate.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'Group2 Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GLM");
        var accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code='GLM'", Long.class, orgId);
        jdbcTemplate.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'CONFIRMED',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbcTemplate.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbcTemplate.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);
        jdbcTemplate.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?", attemptId, batchId);
        jdbcTemplate.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,'2026-01-01 00:00:00','2026-01-02 00:00:00',
                    'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "g2:" + suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    private long insertOrganization(String name, String slug) {
        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbcTemplate.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Group2 Schema User");
        return jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }
}
