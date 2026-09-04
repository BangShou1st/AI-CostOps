package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** M13-B V21 contract tests against real MySQL. */
@SpringBootTest
@Tag("integration")
class GatewaySettlementSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationCreatesSettlementAndForwardLedgerContract() {
        assertThat(tableExists("gateway_settlement")).isTrue();
        assertThat(columnType("gateway_settlement", "calculated_amount_raw"))
                .isEqualTo("decimal(38,18)");
        assertThat(columnType("gateway_settlement", "posted_amount"))
                .isEqualTo("decimal(20,8)");
        assertThat(columnType("gateway_settlement", "rounding_delta"))
                .isEqualTo("decimal(38,18)");
        assertThat(columnType("ledger_posting", "posting_actor_type"))
                .isEqualTo("varchar(16)");
        assertThat(columnType("ledger_entry", "source_gateway_settlement_id"))
                .isEqualTo("bigint");

        assertThat(indexes("gateway_settlement"))
                .contains("uq_gateway_settlement_org_key",
                        "uq_gateway_settlement_org_request",
                        "uq_gateway_settlement_org_usage");
        assertThat(checks("gateway_settlement")).anyMatch(it -> it.contains("PENDING")
                && it.contains("RETRYABLE_FAILED")
                && it.contains("RECONCILIATION_REQUIRED")
                && it.contains("SETTLED"));
        assertThat(checks("ledger_posting")).anyMatch(it -> it.contains("MEMBER")
                && it.contains("SYSTEM") && it.contains("posted_by_member_id"));
        assertThat(checks("ledger_entry")).anyMatch(it -> it.contains("source_charge_fact_id")
                && it.contains("source_expense_claim_id")
                && it.contains("source_gateway_settlement_id"));
    }

    @Test
    void settlementAndGatewayEntryUseSameOrganizationLineage() {
        assertThat(foreignKey("gateway_settlement", "fk_gateway_settlement_request_org"))
                .containsExactly("gateway_request:id", "gateway_request:org_id");
        assertThat(foreignKey("gateway_settlement", "fk_gateway_settlement_usage_org"))
                .containsExactly("gateway_usage_fact:id", "gateway_usage_fact:org_id");
        assertThat(foreignKey("gateway_settlement", "fk_gateway_settlement_reservation_org"))
                .containsExactly("budget_reservation:id", "budget_reservation:org_id");
        assertThat(foreignKey("ledger_entry", "fk_ledger_entry_gateway_settlement_org"))
                .containsExactly("gateway_settlement:id", "gateway_settlement:org_id");
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, Integer.class, table) == 1;
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private List<String> indexes(String table) {
        return jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
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

    private List<String> foreignKey(String table, String constraint) {
        return jdbc.queryForList("""
                SELECT CONCAT(referenced_table_name,':',referenced_column_name)
                FROM information_schema.key_column_usage
                WHERE table_schema=DATABASE() AND table_name=? AND constraint_name=?
                ORDER BY ordinal_position
                """, String.class, table, constraint);
    }
}
