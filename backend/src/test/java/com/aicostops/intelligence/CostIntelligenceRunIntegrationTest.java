package com.aicostops.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.intelligence.application.CostIntelligenceRunService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real commit/rollback tests: intentionally no test-managed transaction. */
@SpringBootTest
@Tag("integration")
class CostIntelligenceRunIntegrationTest extends AuthenticationContainersSupport {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);
    @Autowired private CostIntelligenceRunService runs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private long org;

    @BeforeEach
    void createOrganization() {
        var slug = "analysis-run-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at) "
                + "VALUES('Analysis run test',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", slug);
        org = jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM cost_forecast_snapshot WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_intelligence_run WHERE org_id=?", org);
        jdbc.update("DELETE FROM cost_history_coverage_revision WHERE org_id=?", org);
        jdbc.update("DELETE FROM organization WHERE id=?", org);
    }

    @Test
    void failureIsDurableAndPartialResultsAreRolledBack() {
        assertThatThrownBy(() -> runs.run(org, DATE, "USD", 1, (runId, input) -> {
            insertForecast(runId);
            throw new IllegalStateException("Injected analysis failure");
        })).isInstanceOf(IllegalStateException.class);
        assertFailedWithoutPartialResults();
        var called = new AtomicInteger();
        assertThat(runs.run(org, DATE, "USD", 1, (id, input) -> called.incrementAndGet()).status())
                .isEqualTo("FAILED");
        assertThat(called).hasValue(0);
    }

    @Test
    void successCommitsResultsAndReplaysWithoutRerunning() {
        var first = runs.run(org, DATE, "USD", 1, (id, input) -> insertForecast(id));
        var called = new AtomicInteger();
        assertThat(runs.run(org, DATE, "USD", 1, (id, input) -> called.incrementAndGet())).isEqualTo(first);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(called).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=?",
                Integer.class, org)).isEqualTo(1);
    }

    @Test
    void enclosingTransactionRollsBackBeforeFailureIsRecorded() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> runs.run(org, DATE, "USD", 1, (runId, input) -> {
                insertForecast(runId);
                throw new IllegalStateException("Injected nested failure");
            })).isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
        });
        assertFailedWithoutPartialResults();
    }

    private void assertFailedWithoutPartialResults() {
        assertThat(jdbc.queryForObject("SELECT status FROM cost_intelligence_run WHERE org_id=?",
                String.class, org)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM cost_intelligence_run WHERE org_id=?",
                String.class, org)).isEqualTo("IllegalStateException");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_forecast_snapshot WHERE org_id=?",
                Integer.class, org)).isZero();
    }

    private void insertForecast(long runId) {
        jdbc.update("""
                INSERT INTO cost_forecast_snapshot(org_id,run_id,scope_type,scope_key,currency,
                    projected_amount,method,history_bucket_count,confidence,observed_through,created_at)
                VALUES (?,?,'ORGANIZATION','test','USD',10,'RECENT_RUN_RATE',10,'LOW',?,UTC_TIMESTAMP(6))
                """, org, runId, DATE);
    }
}
