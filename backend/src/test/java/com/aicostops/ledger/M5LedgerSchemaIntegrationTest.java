package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
        assertThat(foreignKey("budget_commitment_usage", "fk_budget_commitment_usage_ledger_entry"))
                .containsExactly("ledger_entry:id", "ledger_entry:org_id");
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
}
