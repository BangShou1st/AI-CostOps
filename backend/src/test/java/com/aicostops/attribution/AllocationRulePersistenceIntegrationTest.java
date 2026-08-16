package com.aicostops.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.application.AllocationTargetDirectory;
import com.aicostops.attribution.application.NewAllocationRuleVersion;
import com.aicostops.attribution.domain.AllocationRuleMatchType;
import com.aicostops.attribution.domain.AllocationRuleStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence foundation of {@code allocation_rule}: append-only immutable
 * versions, half-open effective-range overlap detection for the same key, and
 * the read-only active-target directory the Group 3 workflows will guard with.
 */
@SpringBootTest
@Tag("integration")
class AllocationRulePersistenceIntegrationTest extends MySqlContainerSupport {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FEB_1 = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant MAR_1 = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private AllocationRuleRepository rules;

    @Autowired
    private AllocationTargetDirectory targets;

    @Autowired
    private JdbcTemplate jdbc;

    private long fixtureCounter;

    private long orgId;
    private long otherOrgId;
    private long memberId;
    private long projectId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Rule Org", "rule-" + suffix);
        otherOrgId = insertOrganization("Rule Other", "rule-other-" + suffix);
        var userId = insertUser(suffix + "@example.com");
        memberId = insertMember(orgId, userId);
        projectId = insertProject(orgId, "P-RULE");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void storesVersionsAppendOnlyAndReturnsThemAscending() {
        insertRule("team-charge", 1, 10, JAN_1, FEB_1);
        var v2 = insertRule("team-charge", 2, 11, FEB_1, null);

        var versions = rules.versionsOfKey(orgId, "team-charge");

        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(1).version()).isEqualTo(2);
        assertThat(rules.maxVersion(orgId, "team-charge")).isEqualTo(2);
        assertThat(rules.maxVersion(orgId, "missing-key")).isZero();

        var found = rules.findByKeyAndVersion(orgId, "team-charge", 2).orElseThrow();
        assertThat(found.id()).isEqualTo(v2);
        assertThat(found.status()).isEqualTo(AllocationRuleStatus.ACTIVE);
        assertThat(found.effectiveTo()).isNull();
        assertThat(rules.findByIdAndOrganization(orgId, v2)).isPresent();
        assertThat(rules.findByIdAndOrganization(otherOrgId, v2)).isEmpty();
    }

    @Test
    void rejectsDuplicateKeyVersionPerOrganization() {
        insertRule("team-charge", 1, 10, JAN_1, FEB_1);

        assertThatThrownBy(() -> insertRule("team-charge", 1, 20, JAN_1, FEB_1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_allocation_rule_key_version");
    }

    @Test
    void historicalVersionsMayShareOnePriority() {
        insertRule("team-charge", 1, 10, JAN_1, FEB_1);
        insertRule("team-charge", 2, 10, FEB_1, null);

        assertThat(rules.versionsOfKey(orgId, "team-charge"))
                .extracting(rule -> rule.priority())
                .containsExactly(10, 10);
    }

    @Test
    void overlapDetectionFollowsHalfOpenSemantics() {
        insertRule("team-charge", 1, 10, JAN_1, FEB_1);

        // real overlap: new range crosses the existing one
        assertThat(rules.existsActiveOverlapSameKey(orgId, "team-charge",
                Instant.parse("2026-01-15T00:00:00Z"), MAR_1)).isTrue();
        // adjacency is not overlap: [Jan,Feb) then [Feb,Mar)
        assertThat(rules.existsActiveOverlapSameKey(orgId, "team-charge", FEB_1, MAR_1)).isFalse();
        // an open-ended existing range overlaps everything that starts before its end
        insertRule("open-ended", 1, 20, JAN_1, null);
        assertThat(rules.existsActiveOverlapSameKey(orgId, "open-ended", FEB_1, MAR_1)).isTrue();
        // another key never conflicts
        assertThat(rules.existsActiveOverlapSameKey(orgId, "other-key", JAN_1, FEB_1)).isFalse();
        // ARCHIVED versions do not block new ranges
        jdbc.update("UPDATE allocation_rule SET status='ARCHIVED' WHERE rule_key='team-charge'");
        assertThat(rules.existsActiveOverlapSameKey(orgId, "team-charge",
                Instant.parse("2026-01-15T00:00:00Z"), MAR_1)).isFalse();
    }

    @Test
    void matcherColumnsAreExplicitProviderHintsOnly() {
        insertRule("by-api-key", 1, 10, AllocationRuleMatchType.PROVIDER_API_KEY, JAN_1, FEB_1);
        insertRule("by-project", 1, 11, AllocationRuleMatchType.PROVIDER_PROJECT, JAN_1, FEB_1);
        insertRule("by-user", 1, 12, AllocationRuleMatchType.PROVIDER_USER, JAN_1, FEB_1);

        var stored = rules.versionsOfKey(orgId, "by-api-key").getFirst();
        assertThat(stored.matchHintType()).isEqualTo(AllocationRuleMatchType.PROVIDER_API_KEY);

        var columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'allocation_rule'
                """, String.class);
        assertThat(columns).doesNotContain("match_config_json", "dimension", "expression");
    }

    @Test
    void targetDirectoryResolvesActiveSameOrgRowsOnly() {
        var archivedProject = insertProject(orgId, "P-ARCH");
        jdbc.update("UPDATE project SET status='ARCHIVED' WHERE id=?", archivedProject);
        var otherOrgProject = insertProject(otherOrgId, "P-OTHER");
        var costCenterId = insertCostCenter(orgId, "CC-ACTIVE");
        var teamId = insertTeam(orgId, "T-ACTIVE");

        assertThat(targets.activeProjectExists(orgId, projectId)).isTrue();
        assertThat(targets.activeProjectExists(orgId, archivedProject)).isFalse();
        assertThat(targets.activeProjectExists(orgId, otherOrgProject)).isFalse();
        assertThat(targets.activeCostCenterExists(orgId, costCenterId)).isTrue();
        assertThat(targets.activeTeamExists(orgId, teamId)).isTrue();
        assertThat(targets.activeProjectExists(orgId, 999999L)).isFalse();
    }

    // -- fixtures ----------------------------------------------------------------

    private long insertRule(String ruleKey, int version, int priority, Instant from, Instant to) {
        return insertRule(ruleKey, version, priority, AllocationRuleMatchType.PROVIDER_USER, from, to);
    }

    private long insertRule(String ruleKey, int version, int priority,
            AllocationRuleMatchType matchHintType, Instant from, Instant to) {
        return rules.insertVersion(new NewAllocationRuleVersion(
                orgId, ruleKey, version, "Rule " + ruleKey + " v" + version, "GLM", null,
                matchHintType, "match-" + ruleKey, priority, projectId, null, null, from, to,
                memberId));
    }

    private long insertProject(long org, String code) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Rule Fixture Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM project WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    private long insertCostCenter(long org, String code) {
        jdbc.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Rule Fixture Cost Center','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM cost_center WHERE org_id=? AND code=?",
                Long.class, org, code);
    }

    private long insertTeam(long org, String code) {
        jdbc.update("""
                INSERT INTO team(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Rule Fixture Team','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
                """, email, "Rule Fixture User");
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
