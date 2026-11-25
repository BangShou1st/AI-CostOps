package com.aicostops.gateway.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class GatewayArchitectureSqlValidatorTest {

    @Test
    void acceptsReservedAmountConditionalUpdate() {
        var sql = GatewayArchitectureTest.normalizeSql("""
                UPDATE budget
                SET reserved_amount = reserved_amount + #{amount},
                    version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE org_id=#{orgId} AND id=#{budgetId} AND status='ACTIVE'
                  AND total_amount - actual_amount - committed_amount - reserved_amount >= #{amount}
                """);
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(sql)).isNull();
    }

    @Test
    void rejectsForbiddenBudgetColumnMutationsAndBackendTables() {
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(
                GatewayArchitectureTest.normalizeSql(
                        "UPDATE budget SET actual_amount = actual_amount + #{amount} WHERE id=#{id}")))
                .contains("actual_amount");
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(
                GatewayArchitectureTest.normalizeSql(
                        "UPDATE budget SET reserved_amount = reserved_amount - #{amount}, committed_amount = 0 WHERE id=#{id}")))
                .contains("committed_amount");
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(
                GatewayArchitectureTest.normalizeSql(
                        "UPDATE budget SET total_amount = 100 WHERE id=#{id}")))
                .contains("total_amount");
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(
                GatewayArchitectureTest.normalizeSql(
                        "INSERT INTO budget(org_id, total_amount) VALUES (1, 100)")))
                .contains("never INSERT/DELETE");
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(
                GatewayArchitectureTest.normalizeSql(
                        "INSERT INTO ledger_entry(org_id) VALUES (1)")))
                .contains("ledger_*");
        assertThat(GatewayArchitectureTest.findMutatingSqlViolation(
                GatewayArchitectureTest.normalizeSql(
                        "UPDATE gateway_settlement SET status='SETTLED' WHERE id=1")))
                .contains("gateway_settlement");
    }
}
