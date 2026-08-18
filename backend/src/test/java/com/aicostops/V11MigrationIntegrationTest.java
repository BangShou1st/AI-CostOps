package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Canonical AIC-041 schema contract. V11 establishes BillingPeriod/Budget/
 * BudgetCommitment persistence without implementing the later OPEN guard,
 * activation lifecycle, or Ledger posting behavior.
 */
@SpringBootTest
@Tag("integration")
class V11MigrationIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong fixture = new AtomicLong();
    private long orgId;

    @BeforeEach
    void setUp() {
        var suffix = fixture.incrementAndGet() + "-" + System.nanoTime();
        orgId = insertOrganization("V11 Org " + suffix, "v11-" + suffix);
    }

    @Test
    void migratesAllVersionsThroughV11() {
        var successfulVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class);

        assertThat(successfulVersions).contains("11");
        assertThat(successfulVersions)
                .contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    void createsCanonicalBudgetPeriodTablesAndStableConstraints() {
        var tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'billing_period',
                    'budget',
                    'budget_commitment',
                    'budget_commitment_usage'
                  )
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "billing_period", "budget", "budget_commitment", "budget_commitment_usage");

        var constraints = jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name IN (
                    'uq_billing_period_id_org',
                    'uq_billing_period_org_range',
                    'fk_budget_period_org',
                    'uq_budget_id_org',
                    'uq_budget_scope_currency',
                    'fk_budget_commitment_budget_org',
                    'uq_budget_commitment_id_org',
                    'fk_budget_commitment_usage_commitment_org',
                    'uq_budget_commitment_usage_lineage'
                  )
                """, String.class);
        assertThat(constraints).containsExactlyInAnyOrder(
                "uq_billing_period_id_org",
                "uq_billing_period_org_range",
                "fk_budget_period_org",
                "uq_budget_id_org",
                "uq_budget_scope_currency",
                "fk_budget_commitment_budget_org",
                "uq_budget_commitment_id_org",
                "fk_budget_commitment_usage_commitment_org",
                "uq_budget_commitment_usage_lineage");
    }

    @Test
    void moneyColumnsUseDecimal20Scale8AndActualMayBeNegative() {
        var moneyColumns = jdbc.queryForList("""
                SELECT table_name, column_name, numeric_precision, numeric_scale
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND (
                    (table_name = 'budget'
                      AND column_name IN ('total_amount','actual_amount','committed_amount'))
                    OR
                    (table_name = 'budget_commitment'
                      AND column_name IN ('requested_amount','approved_amount','remaining_amount'))
                    OR
                    (table_name = 'budget_commitment_usage'
                      AND column_name = 'consumed_amount')
                  )
                """);
        assertThat(moneyColumns).hasSize(7);
        assertThat(moneyColumns).allSatisfy(row -> {
            assertThat(((Number) row.get("NUMERIC_PRECISION")).intValue()).isEqualTo(20);
            assertThat(((Number) row.get("NUMERIC_SCALE")).intValue()).isEqualTo(8);
        });

        var periodId = insertPeriod(orgId, "2026-08-01", "2026-09-01", "OPEN");
        var budgetId = insertBudget(orgId, periodId, 501L,
                "100.00000000", "-5.25000000", "10.00000000");

        var actual = jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id = ?", BigDecimal.class, budgetId);
        assertThat(actual).isEqualByComparingTo("-5.25000000");

        assertThatThrownBy(() -> insertBudget(orgId, periodId, 502L,
                "-1.00000000", "0.00000000", "0.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBudget(orgId, periodId, 503L,
                "10.00000000", "0.00000000", "-1.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void billingPeriodEnforcesHalfOpenRangeAndFrozenStatuses() {
        insertPeriod(orgId, "2026-08-01", "2026-09-01", "OPEN");

        assertThatThrownBy(() -> insertPeriod(
                orgId, "2026-09-01", "2026-09-01", "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPeriod(
                orgId, "2026-10-01", "2026-09-01", "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPeriod(
                orgId, "2026-11-01", "2026-12-01", "INVALID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void budgetIdentityIsUniqueWithinPeriodScopeAndCurrency() {
        var periodId = insertPeriod(orgId, "2027-01-01", "2027-02-01", "OPEN");
        insertBudget(orgId, periodId, 700L,
                "100.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> insertBudget(orgId, periodId, 700L,
                "200.00000000", "0.00000000", "0.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void budgetPeriodReferenceCannotCrossOrganizations() {
        var otherOrgId = insertOrganization(
                "V11 Foreign Org " + fixture.incrementAndGet(),
                "v11-foreign-" + fixture.incrementAndGet() + "-" + System.nanoTime());
        var foreignPeriodId = insertPeriod(
                otherOrgId, "2027-02-01", "2027-03-01", "OPEN");

        assertThatThrownBy(() -> insertBudget(orgId, foreignPeriodId, 800L,
                "100.00000000", "0.00000000", "0.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commitmentEnforcesFrozenStatusesAndSameOrgBudgetReference() {
        var periodId = insertPeriod(orgId, "2027-03-01", "2027-04-01", "OPEN");
        var budgetId = insertBudget(orgId, periodId, 900L,
                "100.00000000", "0.00000000", "0.00000000");

        for (var status : new String[] {
                "REQUESTED", "ACTIVE", "PARTIALLY_CONSUMED", "CONSUMED",
                "RELEASED", "REJECTED", "CANCELED"
        }) {
            insertCommitment(orgId, budgetId, status);
        }
        assertThatThrownBy(() -> insertCommitment(orgId, budgetId, "INVALID"))
                .isInstanceOf(DataIntegrityViolationException.class);

        var otherOrgId = insertOrganization(
                "V11 Foreign Commitment " + fixture.incrementAndGet(),
                "v11-foreign-commitment-" + fixture.incrementAndGet() + "-" + System.nanoTime());
        assertThatThrownBy(() -> insertCommitment(otherOrgId, budgetId, "REQUESTED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usageKeepsSameOrgCommitmentLineageAndStagesLedgerForeignKey() {
        var periodId = insertPeriod(orgId, "2027-04-01", "2027-05-01", "OPEN");
        var budgetId = insertBudget(orgId, periodId, 1000L,
                "100.00000000", "0.00000000", "25.00000000");
        var commitmentId = insertCommitment(orgId, budgetId, "ACTIVE");

        insertUsage(orgId, commitmentId, 50001L, "10.00000000");

        assertThatThrownBy(() -> insertUsage(orgId, commitmentId, 50001L, "1.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);

        var otherOrgId = insertOrganization(
                "V11 Foreign Usage " + fixture.incrementAndGet(),
                "v11-foreign-usage-" + fixture.incrementAndGet() + "-" + System.nanoTime());
        assertThatThrownBy(() -> insertUsage(otherOrgId, commitmentId, 50002L, "1.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class);

        var ledgerEntryColumn = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'budget_commitment_usage'
                  AND column_name = 'ledger_entry_id'
                """, Integer.class);
        assertThat(ledgerEntryColumn).isEqualTo(1);

        // AIC-047 creates ledger_entry. AIC-041 may reserve the lineage id but
        // must not create a foreign key to a table that does not exist yet.
        var ledgerForeignKeys = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'budget_commitment_usage'
                  AND column_name = 'ledger_entry_id'
                  AND referenced_table_name = 'ledger_entry'
                """, Integer.class);
        assertThat(ledgerForeignKeys).isZero();
    }

    @Test
    void createsIndexesNeededByPeriodBudgetCommitmentAndLineageReads() {
        var indexes = jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND index_name IN (
                    'idx_billing_period_org_status_range',
                    'idx_budget_org_period_status',
                    'idx_budget_org_scope',
                    'idx_budget_commitment_org_budget_status',
                    'idx_budget_commitment_usage_org_commitment',
                    'idx_budget_commitment_usage_org_ledger_entry'
                  )
                """, String.class);
        assertThat(indexes).containsExactlyInAnyOrder(
                "idx_billing_period_org_status_range",
                "idx_budget_org_period_status",
                "idx_budget_org_scope",
                "idx_budget_commitment_org_budget_status",
                "idx_budget_commitment_usage_org_commitment",
                "idx_budget_commitment_usage_org_ledger_entry");
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
                    org_id, period_start, period_end, status, close_generation,
                    version, created_at, updated_at)
                VALUES (?,CAST(? AS DATE),CAST(? AS DATE),?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, start, end, status);
        return jdbc.queryForObject("""
                SELECT id FROM billing_period
                WHERE org_id = ? AND period_start = CAST(? AS DATE) AND period_end = CAST(? AS DATE)
                """, Long.class, org, start, end);
    }

    private long insertBudget(
            long org,
            long periodId,
            long scopeId,
            String total,
            String actual,
            String committed) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id, billing_period_id, scope_type, scope_id, currency,
                    total_amount, actual_amount, committed_amount, status, version,
                    created_at, updated_at)
                VALUES (?,?,'PROJECT',?,'CNY',CAST(? AS DECIMAL(20,8)),
                    CAST(? AS DECIMAL(20,8)),CAST(? AS DECIMAL(20,8)),'ACTIVE',0,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, periodId, scopeId, total, actual, committed);
        return jdbc.queryForObject("""
                SELECT id FROM budget
                WHERE org_id = ? AND billing_period_id = ? AND scope_type = 'PROJECT'
                  AND scope_id = ? AND currency = 'CNY'
                """, Long.class, org, periodId, scopeId);
    }

    private long insertCommitment(long org, long budgetId, String status) {
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id, budget_id, status, requested_amount, approved_amount,
                    remaining_amount, version, created_at, updated_at)
                VALUES (?,?,?,'10.00000000',NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, budgetId, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM budget_commitment WHERE org_id = ?", Long.class, org);
    }

    private void insertUsage(long org, long commitmentId, long ledgerEntryId, String consumed) {
        jdbc.update("""
                INSERT INTO budget_commitment_usage(
                    org_id, budget_commitment_id, ledger_entry_id, consumed_amount, created_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(6))
                """, org, commitmentId, ledgerEntryId, consumed);
    }
}
