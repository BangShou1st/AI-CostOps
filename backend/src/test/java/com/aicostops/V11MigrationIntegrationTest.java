package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11 migration contract: clean V1->V11 apply, the four M4 budget-period tables
 * and their constraints — billing period range/status/identity, budget
 * identity and money guards (negative actual allowed, negative total /
 * committed rejected), commitment statuses and amount guards, and the
 * append-only usage lineage with a stable (org, commitment, ledger entry)
 * uniqueness but no FK to the not-yet-existing {@code ledger_entry} table.
 */
@SpringBootTest
@Tag("integration")
class V11MigrationIntegrationTest extends MySqlContainerSupport {

    private static final List<String> COMMITMENT_STATUSES = List.of(
            "REQUESTED", "ACTIVE", "PARTIALLY_CONSUMED", "CONSUMED",
            "RELEASED", "REJECTED", "CANCELED");

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong fixture = new AtomicLong();

    private long orgId;
    private long otherOrgId;

    @BeforeEach
    void setUp() {
        var suffix = fixture.incrementAndGet() + "-" + System.nanoTime();
        orgId = insertOrganization("V11 Org " + suffix, "v11-" + suffix);
        otherOrgId = insertOrganization("V11 Foreign " + suffix, "v11-foreign-" + suffix);
    }

    @Test
    void migratesAllVersionsThroughV11() {
        var successfulVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class);
        assertThat(successfulVersions).contains("11");
        assertThat(successfulVersions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    void createsBudgetPeriodTables() {
        var tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('billing_period','budget','budget_commitment','budget_commitment_usage')
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "billing_period", "budget", "budget_commitment", "budget_commitment_usage");
    }

    // -- billing_period ---------------------------------------------------------

    @Test
    void billingPeriodAcceptsValidRangeAndStatuses() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000", "2026-09-01 00:00:00.000000",
                "OPEN");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM billing_period WHERE id=? AND org_id=?
                """, String.class, periodId, orgId)).isEqualTo("OPEN");

        for (var status : List.of("CLOSING", "CLOSED")) {
            jdbc.update("""
                    UPDATE billing_period SET status=?, updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND org_id=?
                    """, status, periodId, orgId);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM billing_period WHERE id=? AND org_id=?",
                    String.class, periodId, orgId)).isEqualTo(status);
        }
    }

    @Test
    void billingPeriodRejectsReversedAndEqualRanges() {
        // MySQL 8.4 reports CHECK violations with SQLState HY000, which
        // surfaces as UncategorizedSQLException (a RuntimeException) carrying
        // the constraint name rather than a typed integrity error.
        assertCheckViolation(() -> insertPeriod(orgId,
                "2026-09-01 00:00:00.000000", "2026-08-01 00:00:00.000000", "OPEN"),
                "chk_billing_period_range");
        assertCheckViolation(() -> insertPeriod(orgId,
                "2026-08-01 00:00:00.000000", "2026-08-01 00:00:00.000000", "OPEN"),
                "chk_billing_period_range");
    }

    @Test
    void billingPeriodRejectsUnknownStatus() {
        assertCheckViolation(() -> insertPeriod(orgId,
                "2026-08-01 00:00:00.000000", "2026-09-01 00:00:00.000000", "FROZEN"),
                "chk_billing_period_status");
    }

    @Test
    void billingPeriodRejectsDuplicateOrgRange() {
        insertPeriod(orgId, "2026-08-01 00:00:00.000000", "2026-09-01 00:00:00.000000", "OPEN");
        assertThatThrownBy(() -> insertPeriod(orgId,
                "2026-08-01 00:00:00.000000", "2026-09-01 00:00:00.000000", "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -- budget -----------------------------------------------------------------

    @Test
    void budgetMoneyColumnsAreDecimal20x8() {
        for (var column : List.of("total_amount", "actual_amount", "committed_amount")) {
            var type = jdbc.queryForObject("""
                    SELECT CONCAT(DATA_TYPE,'(',NUMERIC_PRECISION,',',NUMERIC_SCALE,')')
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='budget' AND column_name=?
                    """, String.class, column);
            assertThat(type).as("budget.%s", column).isEqualTo("decimal(20,8)");
        }
        for (var column : List.of("requested_amount", "approved_amount", "remaining_amount")) {
            var type = jdbc.queryForObject("""
                    SELECT CONCAT(DATA_TYPE,'(',NUMERIC_PRECISION,',',NUMERIC_SCALE,')')
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='budget_commitment' AND column_name=?
                    """, String.class, column);
            assertThat(type).as("budget_commitment.%s", column).isEqualTo("decimal(20,8)");
        }
        var consumedType = jdbc.queryForObject("""
                SELECT CONCAT(DATA_TYPE,'(',NUMERIC_PRECISION,',',NUMERIC_SCALE,')')
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='budget_commitment_usage'
                  AND column_name='consumed_amount'
                """, String.class);
        assertThat(consumedType).isEqualTo("decimal(20,8)");
    }

    @Test
    void budgetAcceptsNegativeActualButRejectsNegativeTotalAndCommitted() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var budgetId = insertBudget(orgId, periodId, "PROJECT", 1001L, "CNY",
                "1000.00000000", "-50.00000000", "0.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", String.class, budgetId))
                .isEqualTo("-50.00000000");

        assertCheckViolation(() -> insertBudget(orgId, periodId, "PROJECT", 1002L, "CNY",
                "-1.00000000", "0.00000000", "0.00000000"), "chk_budget_total_amount");
        assertCheckViolation(() -> insertBudget(orgId, periodId, "PROJECT", 1003L, "CNY",
                "1000.00000000", "0.00000000", "-1.00000000"), "chk_budget_committed_amount");
    }

    @Test
    void budgetPeriodMustBelongToSameOrganization() {
        var foreignPeriodId = insertPeriod(otherOrgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        assertThatThrownBy(() -> insertBudget(orgId, foreignPeriodId, "PROJECT", 1004L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void budgetIdentityIsUniquePerOrgPeriodScopeAndCurrency() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        insertBudget(orgId, periodId, "PROJECT", 1005L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        assertThatThrownBy(() -> insertBudget(orgId, periodId, "PROJECT", 1005L, "CNY",
                "2000.00000000", "0.00000000", "0.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The same scope in another currency is a different budget identity.
        insertBudget(orgId, periodId, "PROJECT", 1005L, "USD",
                "1000.00000000", "0.00000000", "0.00000000");
        // The same currency on another scope is a different budget identity.
        insertBudget(orgId, periodId, "TEAM", 1006L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
    }

    // -- budget_commitment ------------------------------------------------------

    @Test
    void commitmentSupportsExactlyTheFrozenStatuses() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var budgetId = insertBudget(orgId, periodId, "PROJECT", 1007L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        for (var status : COMMITMENT_STATUSES) {
            jdbc.update("""
                    INSERT INTO budget_commitment(
                        org_id,budget_id,status,requested_amount,approved_amount,remaining_amount,
                        version,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, budgetId, status, "100.00000000",
                    "REQUESTED".equals(status) ? null : "100.00000000",
                    "REQUESTED".equals(status) ? null : "100.00000000");
        }
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT status FROM budget_commitment WHERE org_id=?
                """, String.class, orgId)).containsExactlyInAnyOrderElementsOf(COMMITMENT_STATUSES);

        assertCheckViolation(() -> jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,approved_amount,remaining_amount,
                    version,created_at,updated_at)
                VALUES (?,?,'RESERVED','100.00000000',NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, budgetId), "chk_budget_commitment_status");
    }

    @Test
    void commitmentAmountGuardsAreEnforced() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var budgetId = insertBudget(orgId, periodId, "PROJECT", 1008L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        // requested_amount must be > 0.
        assertCheckViolation(() -> insertCommitment(orgId, budgetId, "REQUESTED",
                "0.00000000", null, null), "chk_budget_commitment_requested");
        assertCheckViolation(() -> insertCommitment(orgId, budgetId, "REQUESTED",
                "-1.00000000", null, null), "chk_budget_commitment_requested");

        // approved_amount and remaining_amount must be >= 0 when non-null.
        assertCheckViolation(() -> insertCommitment(orgId, budgetId, "ACTIVE",
                "100.00000000", "-1.00000000", "100.00000000"), "chk_budget_commitment_approved");
        assertCheckViolation(() -> insertCommitment(orgId, budgetId, "ACTIVE",
                "100.00000000", "100.00000000", "-1.00000000"), "chk_budget_commitment_remaining");

        // NULL amounts are legal (REQUESTED state has no approval yet).
        insertCommitment(orgId, budgetId, "REQUESTED", "100.00000000", null, null);
    }

    @Test
    void commitmentBudgetMustBelongToSameOrganization() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var foreignBudgetId = insertBudget(otherOrgId,
                insertPeriod(otherOrgId, "2026-08-01 00:00:00.000000",
                        "2026-09-01 00:00:00.000000", "OPEN"),
                "PROJECT", 1009L, "CNY", "1000.00000000", "0.00000000", "0.00000000");
        assertThatThrownBy(() -> insertCommitment(orgId, foreignBudgetId, "REQUESTED",
                "100.00000000", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(periodId).isNotZero();
    }

    // -- budget_commitment_usage -------------------------------------------------

    @Test
    void usageLineageIsUniqueAndConsumedAmountMustBePositive() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var budgetId = insertBudget(orgId, periodId, "PROJECT", 1010L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var commitmentId = insertCommitment(orgId, budgetId, "ACTIVE",
                "100.00000000", "100.00000000", "100.00000000");

        insertUsage(orgId, commitmentId, 9001L, "40.00000000");
        insertUsage(orgId, commitmentId, 9002L, "60.00000000");
        assertThatThrownBy(() -> insertUsage(orgId, commitmentId, 9001L, "1.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCheckViolation(() -> insertUsage(orgId, commitmentId, 9003L, "0.00000000"),
                "chk_budget_commitment_usage_consumed");
        assertCheckViolation(() -> insertUsage(orgId, commitmentId, 9004L, "-1.00000000"),
                "chk_budget_commitment_usage_consumed");
    }

    @Test
    void usageCommitmentMustBelongToSameOrganization() {
        var periodId = insertPeriod(orgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var budgetId = insertBudget(orgId, periodId, "PROJECT", 1011L, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var foreignCommitmentId = insertCommitment(otherOrgId,
                insertBudget(otherOrgId,
                        insertPeriod(otherOrgId, "2026-08-01 00:00:00.000000",
                                "2026-09-01 00:00:00.000000", "OPEN"),
                        "PROJECT", 1012L, "CNY", "1000.00000000", "0.00000000", "0.00000000"),
                "ACTIVE", "100.00000000", "100.00000000", "100.00000000");
        assertThatThrownBy(() -> insertUsage(orgId, foreignCommitmentId, 9005L, "1.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerEntryIdExistsButHasNoForeignKeyYet() {
        var column = jdbc.queryForList("""
                SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='budget_commitment_usage'
                  AND column_name='ledger_entry_id'
                """);
        assertThat(column).hasSize(1);
        assertThat(((String) column.get(0).get("COLUMN_NAME"))).isEqualTo("ledger_entry_id");
        assertThat(((String) column.get(0).get("IS_NULLABLE"))).isEqualTo("NO");

        var ledgerFks = jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE()
                  AND table_name='budget_commitment_usage'
                  AND constraint_type='FOREIGN KEY'
                """, String.class);
        for (var constraint : ledgerFks) {
            var referenced = jdbc.queryForList("""
                    SELECT DISTINCT REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE
                    WHERE constraint_schema=DATABASE() AND constraint_name=?
                    """, String.class, constraint);
            assertThat(referenced).as("FK %s must not reference ledger_entry", constraint)
                    .doesNotContain("ledger_entry");
        }
    }

    // -- fixtures -------------------------------------------------------------

    /**
     * MySQL 8.4 reports CHECK violations with SQLState HY000 (error 3819),
     * which JdbcTemplate surfaces as UncategorizedSQLException — a
     * RuntimeException carrying the constraint name — rather than a typed
     * integrity error. UNIQUE / FK violations are asserted elsewhere with
     * {@link DataIntegrityViolationException} directly.
     */
    private static void assertCheckViolation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            String constraintName) {
        assertThatThrownBy(callable)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(constraintName);
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
    }

    private long insertPeriod(long org, String start, String end, String status) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,?,?,?,0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, start, end, status);
        return jdbc.queryForObject("""
                SELECT id FROM billing_period
                WHERE org_id=? AND period_start=? AND period_end=?
                """, Long.class, org, start, end);
    }

    private long insertBudget(long org, long periodId, String scopeType, long scopeId,
            String currency, String total, String actual, String committed) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, periodId, scopeType, scopeId, currency, total, actual, committed);
        return jdbc.queryForObject("""
                SELECT id FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type=? AND scope_id=? AND currency=?
                """, Long.class, org, periodId, scopeType, scopeId, currency);
    }

    private long insertCommitment(long org, long budgetId, String status,
            String requested, String approved, String remaining) {
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,approved_amount,remaining_amount,
                    version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, budgetId, status, requested, approved, remaining);
        return jdbc.queryForObject("""
                SELECT id FROM budget_commitment
                WHERE org_id=? AND budget_id=? AND status=?
                ORDER BY id DESC LIMIT 1
                """, Long.class, org, budgetId, status);
    }

    private long insertUsage(long org, long commitmentId, long ledgerEntryId, String consumed) {
        jdbc.update("""
                INSERT INTO budget_commitment_usage(
                    org_id,budget_commitment_id,ledger_entry_id,consumed_amount,created_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6))
                """, org, commitmentId, ledgerEntryId, consumed);
        return jdbc.queryForObject("""
                SELECT id FROM budget_commitment_usage
                WHERE org_id=? AND budget_commitment_id=? AND ledger_entry_id=?
                """, Long.class, org, commitmentId, ledgerEntryId);
    }
}
