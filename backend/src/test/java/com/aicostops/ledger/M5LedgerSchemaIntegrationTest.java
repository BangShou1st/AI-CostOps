package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class M5LedgerSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v13CreatesImmutableLedgerAndCompletesCommitmentLineage() {
        assertThat(tableExists("ledger_posting")).isTrue();
        assertThat(tableExists("ledger_entry")).isTrue();
        assertThat(tableExists("correction_group")).isTrue();
        assertThat(uniqueColumns("ledger_posting", "uq_ledger_posting_org_key"))
                .containsExactly("org_id", "posting_key");
        assertThat(uniqueColumns("ledger_entry", "uq_ledger_entry_posting_index"))
                .containsExactly("posting_id", "entry_index");
        assertThat(uniqueColumns("correction_group", "uq_correction_group_target"))
                .containsExactly("org_id", "target_entry_id");
        assertThat(uniqueColumns("project", "uq_project_id_org"))
                .containsExactly("id", "org_id");
        assertThat(uniqueColumns("team", "uq_team_id_org"))
                .containsExactly("id", "org_id");
        assertThat(uniqueColumns("cost_center", "uq_cost_center_id_org"))
                .containsExactly("id", "org_id");
        assertThat(foreignKey("ledger_entry", "fk_ledger_entry_project_org"))
                .containsExactly("project:id", "project:org_id");
        assertThat(foreignKey("ledger_entry", "fk_ledger_entry_team_org"))
                .containsExactly("team:id", "team:org_id");
        assertThat(foreignKey("ledger_entry", "fk_ledger_entry_cost_center_org"))
                .containsExactly("cost_center:id", "cost_center:org_id");
        assertThat(foreignKey("budget_commitment_usage", "fk_budget_commitment_usage_ledger_entry"))
                .containsExactly("ledger_entry:id", "ledger_entry:org_id");
    }

    @Test
    void targetForeignKeysRejectMissingAndCrossOrganizationLedgerTargets() {
        var suffix = UUID.randomUUID().toString();
        var orgId = insertOrganization("schema-org-" + suffix, "schema-org-" + suffix);
        var foreignOrgId = insertOrganization("schema-foreign-" + suffix,
                "schema-foreign-" + suffix);
        var userId = insertUser("schema-" + suffix + "@example.com");
        var memberId = insertMember(orgId, userId);
        var periodId = insertPeriod(orgId);
        var projectId = insertTarget("project", orgId, "schema-p-" + suffix);
        var foreignProjectId = insertTarget("project", foreignOrgId, "schema-fp-" + suffix);
        var teamId = insertTarget("team", orgId, "schema-t-" + suffix);
        var foreignTeamId = insertTarget("team", foreignOrgId, "schema-ft-" + suffix);
        var costCenterId = insertTarget("cost_center", orgId, "schema-c-" + suffix);
        var foreignCostCenterId = insertTarget("cost_center", foreignOrgId, "schema-fc-" + suffix);
        var postingId = insertPosting(orgId, periodId, memberId, "schema-posting-" + suffix);

        try {
            assertThatCode(() -> insertEntry(orgId, postingId, 0, projectId, null, null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> insertEntry(orgId, postingId, 1, null, null, teamId))
                    .doesNotThrowAnyException();
            assertThatCode(() -> insertEntry(orgId, postingId, 2, null, costCenterId, null))
                    .doesNotThrowAnyException();
            assertInvalidTarget(orgId, postingId, 10, 999_999_991L, null, null);
            assertInvalidTarget(orgId, postingId, 11, foreignProjectId, null, null);
            assertInvalidTarget(orgId, postingId, 12, null, null, 999_999_992L);
            assertInvalidTarget(orgId, postingId, 13, null, null, foreignTeamId);
            assertInvalidTarget(orgId, postingId, 14, null, 999_999_993L, null);
            assertInvalidTarget(orgId, postingId, 15, null, foreignCostCenterId, null);
        } finally {
            jdbc.update("DELETE FROM ledger_entry WHERE posting_id=?", postingId);
            jdbc.update("DELETE FROM ledger_posting WHERE id=?", postingId);
            jdbc.update("DELETE FROM billing_period WHERE id=?", periodId);
            jdbc.update("DELETE FROM project WHERE id IN (?, ?)", projectId, foreignProjectId);
            jdbc.update("DELETE FROM team WHERE id IN (?, ?)", teamId, foreignTeamId);
            jdbc.update("DELETE FROM cost_center WHERE id IN (?, ?)", costCenterId,
                    foreignCostCenterId);
            jdbc.update("DELETE FROM organization_member WHERE id=?", memberId);
            jdbc.update("DELETE FROM app_user WHERE id=?", userId);
            jdbc.update("DELETE FROM organization WHERE id IN (?, ?)", orgId, foreignOrgId);
        }
    }

    @Test
    void ledgerColumnsAndChecksPreserveFinancialTruth() {
        assertThat(columnType("ledger_entry", "amount")).startsWith("decimal(20,8)");
        assertThat(checks("ledger_entry")).anyMatch(it -> it.contains("project_id")
                && it.contains("cost_center_id") && it.contains("team_id"));
        assertThat(checks("ledger_entry")).anyMatch(it -> it.contains("source_charge_fact_id")
                && it.contains("source_expense_claim_id"));
        assertThat(checks("ledger_posting")).anyMatch(it -> it.contains("POSTED"));
        assertThat(checks("correction_group")).anyMatch(it -> it.contains("POSTED"));
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, Integer.class, table) == 1;
    }

    private List<String> uniqueColumns(String table, String constraint) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.key_column_usage
                WHERE table_schema=DATABASE() AND table_name=? AND constraint_name=?
                ORDER BY ordinal_position
                """, String.class, table, constraint);
    }

    private List<String> foreignKey(String table, String constraint) {
        return jdbc.queryForList("""
                SELECT CONCAT(referenced_table_name,':',referenced_column_name)
                FROM information_schema.key_column_usage
                WHERE table_schema=DATABASE() AND table_name=? AND constraint_name=?
                ORDER BY ordinal_position
                """, String.class, table, constraint);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private List<String> checks(String table) {
        return jdbc.queryForList("""
                SELECT check_clause FROM information_schema.check_constraints cc
                JOIN information_schema.table_constraints tc
                  ON tc.constraint_schema=cc.constraint_schema
                 AND tc.constraint_name=cc.constraint_name
                WHERE tc.table_schema=DATABASE() AND tc.table_name=?
                """, String.class, table);
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?, ?, 'ACTIVE', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?, 'Schema Test', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,employee_no,default_cost_center_id,
                    status,joined_at)
                VALUES (?, ?, NULL, NULL, 'ACTIVE', UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject("SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, orgId, userId);
    }

    private long insertPeriod(long orgId) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        return jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class,
                orgId);
    }

    private long insertTarget(String type, long orgId, String code) {
        var table = type;
        jdbc.update("""
                INSERT INTO %s(org_id,code,name,status,created_at,updated_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """.formatted(table), orgId, code, code);
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE org_id=? AND code=?",
                Long.class, orgId, code);
    }

    private long insertPosting(long orgId, long periodId, long memberId, String key) {
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,
                    billing_period_id,status,posted_by_member_id,posted_at,created_at)
                VALUES (?, ?, 'PROVIDER_CHARGE', 1, NULL, ?, 'POSTED', ?,
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orgId, key, periodId, memberId);
        return jdbc.queryForObject("SELECT id FROM ledger_posting WHERE org_id=? AND posting_key=?",
                Long.class, orgId, key);
    }

    private void insertEntry(long orgId, long postingId, int index, Long projectId,
            Long costCenterId, Long teamId) {
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,
                    project_id,cost_center_id,team_id,budget_id,
                    source_charge_fact_id,source_expense_claim_id,allocation_line_id,
                    correction_group_id,reverses_entry_id,created_at)
                VALUES (?, ?, ?, 'COST', '1.00000000', 'CNY', ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL,
                    UTC_TIMESTAMP(6))
                """, orgId, postingId, index, projectId, costCenterId, teamId);
    }

    private void assertInvalidTarget(long orgId, long postingId, int index, Long projectId,
            Long costCenterId, Long teamId) {
        assertThatThrownBy(() -> insertEntry(orgId, postingId, index, projectId, costCenterId,
                teamId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
