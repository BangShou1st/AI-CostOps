package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.reconciliation.domain.PeriodCloseCheckResult;
import com.aicostops.reconciliation.domain.PeriodCloseRunStatus;
import com.aicostops.reconciliation.domain.ReconciliationCaseStatus;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.PeriodCloseMapper;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class ReconciliationPersistenceIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationMapper reconciliation;
    @Autowired PeriodCloseMapper close;

    private long orgId;
    private long memberId;
    private long periodId;
    private long providerAccountId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "recon-persistence-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.test", suffix);
        var userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000','OPEN',0,0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,status,created_at,updated_at)
                VALUES (?,'OPENAI',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix);
        providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void mapsRunCaseCloseRunAndCheckUsingTypedEnums() {
        var now = Instant.parse("2026-08-20T04:00:00Z");
        assertThat(reconciliation.insertRun(orgId, periodId, "RUNNING",
                "M6_PERIOD_PROVIDER_CURRENCY_V1", new BigDecimal("0.00000000"), "{}",
                memberId, now, now, now)).isEqualTo(1);
        var runId = reconciliation.lastInsertId();

        var running = reconciliation.selectRunByIdAndOrganization(orgId, runId);
        assertThat(running.status()).isEqualTo(ReconciliationRunStatus.RUNNING);
        assertThat(running.toleranceAmount()).isEqualByComparingTo("0.00000000");

        assertThat(reconciliation.insertCase(orgId, runId, providerAccountId, "USD",
                "AMOUNT_MISMATCH", new BigDecimal("10.00000000"),
                new BigDecimal("12.00000000"), new BigDecimal("2.00000000"),
                1, 1, now, now)).isEqualTo(1);
        var caseId = reconciliation.lastInsertId();
        assertThat(reconciliation.selectCaseByIdAndOrganization(orgId, caseId).status())
                .isEqualTo(ReconciliationCaseStatus.OPEN);

        assertThat(close.insertRun(orgId, periodId, 0, 1, "CHECKING", runId,
                memberId, now, now, now)).isEqualTo(1);
        var closeRunId = close.lastInsertId();
        assertThat(close.selectRunByIdAndOrganization(orgId, closeRunId).status())
                .isEqualTo(PeriodCloseRunStatus.CHECKING);

        assertThat(close.insertCheck(orgId, closeRunId, "OPEN_IMPORTS", "PASS", 0,
                "{}", now, now)).isEqualTo(1);
        assertThat(close.selectChecksByRun(orgId, closeRunId))
                .singleElement()
                .extracting(c -> c.result())
                .isEqualTo(PeriodCloseCheckResult.PASS);
    }

    @Test
    void orgScopedReadsHideForeignRows() {
        var now = Instant.parse("2026-08-20T04:00:00Z");
        reconciliation.insertRun(orgId, periodId, "RUNNING",
                "M6_PERIOD_PROVIDER_CURRENCY_V1", new BigDecimal("0.00000000"), "{}",
                memberId, now, now, now);
        var runId = reconciliation.lastInsertId();

        assertThat(reconciliation.selectRunByIdAndOrganization(orgId + 999, runId)).isNull();
    }
}
