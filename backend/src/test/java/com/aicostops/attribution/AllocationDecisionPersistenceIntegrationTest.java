package com.aicostops.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.attribution.application.AllocationDecisionRepository;
import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.application.NewAllocationDecisionDraft;
import com.aicostops.attribution.application.NewAllocationLine;
import com.aicostops.attribution.application.NewAllocationRuleVersion;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.attribution.domain.AllocationDecisionStatus;
import com.aicostops.attribution.domain.AllocationSubjectType;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence foundation of {@code allocation_decision} and
 * {@code allocation_line}: DRAFT-only inserts with subject/source guards,
 * exact Money representability enforced before the mapper, and the composite
 * same-charge current-decision pointer contract.
 */
@SpringBootTest
@Tag("integration")
class AllocationDecisionPersistenceIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private AllocationDecisionRepository decisions;

    @Autowired
    private AllocationRuleRepository rules;

    @Autowired
    private JdbcTemplate jdbc;

    private long fixtureCounter;

    private long orgId;
    private long otherOrgId;
    private long memberId;
    private long chargeId;
    private long projectId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Decision Org", "dec-" + suffix);
        otherOrgId = insertOrganization("Decision Other", "dec-other-" + suffix);
        var userId = insertUser(suffix + "@example.com");
        memberId = insertMember(orgId, userId);
        projectId = insertProject(orgId, "P-DEC");
        chargeId = insertConfirmedCharge(orgId, memberId, suffix);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void insertsManualChargeDraftAndReadsItBack() {
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));

        var stored = decisions.findByIdAndOrganization(orgId, decisionId).orElseThrow();
        assertThat(stored.subjectType()).isEqualTo(AllocationSubjectType.CHARGE_FACT);
        assertThat(stored.chargeFactId()).isEqualTo(chargeId);
        assertThat(stored.decisionSource()).isEqualTo(AllocationDecisionSource.MANUAL);
        assertThat(stored.allocationRuleId()).isNull();
        assertThat(stored.status()).isEqualTo(AllocationDecisionStatus.DRAFT);
        assertThat(stored.createdByMemberId()).isEqualTo(memberId);
        assertThat(decisions.findByIdAndOrganization(otherOrgId, decisionId)).isEmpty();
        assertThat(decisions.countConfirmedForCharge(orgId, chargeId)).isZero();
    }

    @Test
    void ruleSourceDraftRequiresAndPreservesRuleTrace() {
        var ruleId = rules.insertVersion(new NewAllocationRuleVersion(
                orgId, "trace-rule", 1, "Trace Rule", "GLM", null,
                com.aicostops.attribution.domain.AllocationRuleMatchType.PROVIDER_USER, "u",
                10, projectId, null, null,
                Instant.parse("2026-01-01T00:00:00Z"), null, memberId));

        // MySQL CHECK violations arrive with SQLState HY000 as uncategorized errors.
        assertThatThrownBy(() -> decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.RULE, null, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_allocation_decision_source_rule");

        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.RULE, ruleId, null));

        var stored = decisions.findByIdAndOrganization(orgId, decisionId).orElseThrow();
        assertThat(stored.allocationRuleId()).isEqualTo(ruleId);
        assertThat(stored.createdByMemberId()).isNull();
    }

    @Test
    void expenseClaimSubjectKeepsIdentityWithoutForeignKey() {
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.EXPENSE_CLAIM, null, 4242L,
                AllocationDecisionSource.MANUAL, null, memberId));

        var stored = decisions.findByIdAndOrganization(orgId, decisionId).orElseThrow();
        assertThat(stored.expenseClaimId()).isEqualTo(4242L);
        assertThat(stored.chargeFactId()).isNull();
    }

    @Test
    void secondDraftOfSameChargeIsAllowedButOnlyOneConfirmationSurvives() {
        decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));
        var secondDraft = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));
        assertThat(secondDraft).isPositive();

        jdbc.update("UPDATE allocation_decision SET status='CONFIRMED' WHERE id=?",
                jdbc.queryForObject("SELECT MIN(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                        Long.class, orgId, chargeId));
        assertThat(decisions.countConfirmedForCharge(orgId, chargeId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE allocation_decision SET status='CONFIRMED' WHERE id=?", secondDraft))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_allocation_decision_confirmed_charge");
    }

    @Test
    void currentDecisionPointerRequiresSameCharge() {
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));
        var otherCharge = insertConfirmedCharge(orgId, memberId, "other-charge");

        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, otherCharge))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_charge_fact_current_allocation_decision");
    }

    @Test
    void insertsLinesWithExactMoneyAndDeterministicIndexes() {
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));

        var costCenterId = insertCostCenter(orgId, "CC-DEC");
        var teamId = insertTeam(orgId, "T-DEC");
        decisions.insertLine(new NewAllocationLine(orgId, decisionId, 0,
                new BigDecimal("10.00000000"), "CNY", projectId, null, null));
        decisions.insertLine(new NewAllocationLine(orgId, decisionId, 1,
                new BigDecimal("-2.50000000"), "CNY", null, costCenterId, null));
        decisions.insertLine(new NewAllocationLine(orgId, decisionId, 2,
                BigDecimal.ZERO, "CNY", null, null, teamId));

        var lines = decisions.linesOfDecision(orgId, decisionId);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).lineIndex()).isZero();
        assertThat(lines.get(0).allocatedAmount()).isEqualByComparingTo("10");
        assertThat(lines.get(1).allocatedAmount()).isEqualByComparingTo("-2.5");
        assertThat(lines.get(2).allocatedAmount()).isEqualByComparingTo("0");
        assertThat(lines.get(0).projectId()).isEqualTo(projectId);

        assertThatThrownBy(() -> decisions.insertLine(new NewAllocationLine(
                orgId, decisionId, 0, new BigDecimal("1.00000000"), "CNY", projectId, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_allocation_line_decision_index");
    }

    @Test
    void rejectsInexactMoneyAndInvalidCurrencyBeforeTheMapper() {
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));

        assertThatThrownBy(() -> decisions.insertLine(new NewAllocationLine(
                orgId, decisionId, 0, new BigDecimal("1.123456789"), "CNY", projectId, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.insertLine(new NewAllocationLine(
                orgId, decisionId, 0, new BigDecimal("1.12345678"), "US", projectId, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decisions.insertLine(new NewAllocationLine(
                orgId, decisionId, 0, new BigDecimal("1.12345678"), "USDD", projectId, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(decisions.linesOfDecision(orgId, decisionId)).isEmpty();
    }

    @Test
    void lineRequiresExactlyOneTargetAndMatchingOrganization() {
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.MANUAL, null, memberId));

        assertThatThrownBy(() -> decisions.insertLine(new NewAllocationLine(
                orgId, decisionId, 0, new BigDecimal("1.00000000"), "CNY", null, null, null)))
                .hasStackTraceContaining("chk_allocation_line_target");
        assertThatThrownBy(() -> decisions.insertLine(new NewAllocationLine(
                otherOrgId, decisionId, 0, new BigDecimal("1.00000000"), "CNY", projectId, null, null)))
                .hasStackTraceContaining("fk_allocation_line_decision_org");
    }

    @Test
    void cleanerRemovesAFullDuplicateAttributionGraph() {
        var ruleId = rules.insertVersion(new NewAllocationRuleVersion(
                orgId, "graph-rule", 1, "Graph Rule", "GLM", null,
                com.aicostops.attribution.domain.AllocationRuleMatchType.PROVIDER_USER, "u",
                10, projectId, null, null,
                Instant.parse("2026-01-01T00:00:00Z"), null, memberId));
        var decisionId = decisions.insertDraft(new NewAllocationDecisionDraft(
                orgId, AllocationSubjectType.CHARGE_FACT, chargeId, null,
                AllocationDecisionSource.RULE, ruleId, null));
        decisions.insertLine(new NewAllocationLine(orgId, decisionId, 0,
                new BigDecimal("10.00000000"), "CNY", projectId, null, null));
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        var otherCharge = insertConfirmedCharge(orgId, memberId, "graph-other");
        var low = Math.min(chargeId, otherCharge);
        var high = Math.max(chargeId, otherCharge);
        jdbc.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                    match_reason,status,created_at)
                VALUES (?,?,?,'EXACT',SHA2('graph',256),'v1','graph fixture','OPEN',UTC_TIMESTAMP(6))
                """, orgId, low, high);

        M2DatabaseCleaner.clean(jdbc);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duplicate_candidate",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM allocation_line",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM allocation_decision",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM allocation_rule",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charge_fact",
                Integer.class)).isZero();
    }

    // -- fixtures ----------------------------------------------------------------

    private long insertConfirmedCharge(long org, long memberId, String label) {
        var suffix = label + "-" + ++fixtureCounter + "-" + System.nanoTime();
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
        jdbc.update("""
                INSERT IGNORE INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'Decision Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, "GLM");
        var accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code='GLM'",
                Long.class, org);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'CONFIRMED',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?", attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,'2026-01-01 00:00:00','2026-01-02 00:00:00',
                    'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "dec:" + suffix);
        var rawId = jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,created_at)
                VALUES (?,?,0,'GLM','USAGE',10.0,'CNY',UTC_TIMESTAMP(6))
                """, org, rawId);
        return jdbc.queryForObject("SELECT id FROM charge_fact WHERE raw_record_id=?", Long.class, rawId);
    }

    private long insertProject(long org, String code) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Decision Fixture Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    private long insertCostCenter(long org, String code) {
        jdbc.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Decision Fixture Cost Center','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM cost_center WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    private long insertTeam(long org, String code) {
        jdbc.update("""
                INSERT INTO team(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Decision Fixture Team','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM team WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Decision Fixture User");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    private long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }
}
