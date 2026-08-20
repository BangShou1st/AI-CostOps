package com.aicostops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V16 contract for M6 reconciliation and period-close persistence. */
@SpringBootTest
@Tag("integration")
class M6ReconciliationCloseSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationCreatesAllFourM6TablesAndSupportingIndexes() {
        assertThat(jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN (
                    'reconciliation_run','reconciliation_case',
                    'period_close_run','period_close_check')
                """, String.class))
                .containsExactlyInAnyOrder(
                        "reconciliation_run", "reconciliation_case",
                        "period_close_run", "period_close_check");

        assertThat(indexExists("ledger_posting", "idx_ledger_posting_org_period_id")).isTrue();
        assertThat(indexExists("expense_claim", "idx_expense_claim_org_status_date_id")).isTrue();
        assertThat(indexExists("import_batch", "idx_import_batch_org_status_period_id")).isTrue();
        assertThat(indexExists("provider_account", "uq_provider_account_id_org")).isTrue();
    }

    @Test
    void moneyColumnsRemainDecimal20x8() {
        for (var column : List.of("tolerance_amount")) {
            assertThat(columnType("reconciliation_run", column)).isEqualTo("decimal(20,8)");
        }
        for (var column : List.of("external_amount", "internal_amount", "difference_amount")) {
            assertThat(columnType("reconciliation_case", column)).isEqualTo("decimal(20,8)");
        }
    }

    @Test
    void canonicalCloseBlockerAndResultChecksRejectUnknownValues() {
        var fixture = insertFixture("m6-schema-blocker");
        var closeRunId = insertCloseRun(fixture, "CHECKING", null, null, null);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO period_close_check(
                  org_id,period_close_run_id,blocker_code,result,item_count,
                  summary_json,evaluated_at,created_at)
                VALUES (?,?,?,'PASS',0,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), closeRunId, "NOT_A_BLOCKER"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_period_close_check_blocker");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO period_close_check(
                  org_id,period_close_run_id,blocker_code,result,item_count,
                  summary_json,evaluated_at,created_at)
                VALUES (?,?,'OPEN_IMPORTS',?,0,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), closeRunId, "UNKNOWN"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_period_close_check_result");
    }

    @Test
    void closeRunIdentityIsUniqueWithinGenerationAndAttempt() {
        var fixture = insertFixture("m6-schema-attempt");
        insertCloseRun(fixture, "CHECKING", null, null, null);

        assertThatThrownBy(() -> insertCloseRun(fixture, "CHECKING", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reconciliationCaseIsUniquePerRunProviderAndCurrency() {
        var fixture = insertFixture("m6-schema-case");
        var runId = insertReconciliationRun(fixture, "COMPLETED",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        insertCase(fixture, runId, "AMOUNT_MISMATCH", "10.00000000", "12.00000000",
                "2.00000000", 1, 1);

        assertThatThrownBy(() -> insertCase(fixture, runId, "AMOUNT_MISMATCH",
                "11.00000000", "12.00000000", "1.00000000", 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolvedCaseRequiresCompleteResolutionMetadata() {
        var fixture = insertFixture("m6-schema-resolution");
        var runId = insertReconciliationRun(fixture, "COMPLETED",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reconciliation_case(
                  org_id,reconciliation_run_id,provider_account_id,currency,case_type,
                  external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,
                  reason_code,resolution_note,resolved_by_member_id,resolved_at,
                  created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH',
                  '10.00000000','12.00000000','2.00000000',1,1,'RESOLVED',
                  NULL,NULL,NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), runId, fixture.providerAccountId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_case_resolution");
    }

    private boolean indexExists(String table, String index) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                """, Integer.class, table, index) > 0;
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT CONCAT(DATA_TYPE,'(',NUMERIC_PRECISION,',',NUMERIC_SCALE,')')
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private Fixture insertFixture(String prefix) {
        var suffix = prefix + "-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M6 " + suffix, suffix);
        var orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.test", "M6 User");
        var userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,employee_no,default_cost_center_id,status,joined_at)
                VALUES (?,?,NULL,NULL,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        var memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,closing_started_at,
                  closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                  'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,'OPENAI',?,NULL,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Fixture(orgId, memberId, periodId, providerAccountId);
    }

    private long insertReconciliationRun(Fixture fixture, String status, String basisHash) {
        jdbc.update("""
                INSERT INTO reconciliation_run(
                  org_id,billing_period_id,status,algorithm_version,tolerance_amount,basis_hash,
                  summary_json,created_by_member_id,started_at,finished_at,error_code,error_summary,
                  created_at,updated_at)
                VALUES (?,?,?,'M6_PERIOD_PROVIDER_CURRENCY_V1','0.00000000',?,JSON_OBJECT(),?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), fixture.periodId(), status, basisHash, fixture.memberId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertCloseRun(Fixture fixture, String status, Long reconciliationRunId,
            String errorCode, String errorSummary) {
        var terminal = !"CHECKING".equals(status);
        jdbc.update("""
                INSERT INTO period_close_run(
                  org_id,billing_period_id,close_generation,attempt_no,status,reconciliation_run_id,
                  started_by_member_id,started_at,finished_at,error_code,error_summary,created_at,updated_at)
                VALUES (?,?,0,1,?,?,?,UTC_TIMESTAMP(6),%s,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """.formatted(terminal ? "UTC_TIMESTAMP(6)" : "NULL"),
                fixture.orgId(), fixture.periodId(), status, reconciliationRunId,
                fixture.memberId(), errorCode, errorSummary);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertCase(Fixture fixture, long runId, String caseType,
            String externalAmount, String internalAmount, String difference,
            long externalRows, long internalRows) {
        jdbc.update("""
                INSERT INTO reconciliation_case(
                  org_id,reconciliation_run_id,provider_account_id,currency,case_type,
                  external_amount,internal_amount,difference_amount,external_row_count,
                  internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD',?,?,?,?,?,?,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), runId, fixture.providerAccountId(), caseType,
                externalAmount, internalAmount, difference, externalRows, internalRows);
    }

    private record Fixture(long orgId, long memberId, long periodId, long providerAccountId) {
    }
}
