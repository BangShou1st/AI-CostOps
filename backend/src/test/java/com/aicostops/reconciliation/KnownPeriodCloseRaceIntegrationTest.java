package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BillingPeriodClosePort;
import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Tag("integration")
class KnownPeriodCloseRaceIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired BillingPeriodFinancialWriteFence fence;
    @Autowired BillingPeriodClosePort closePeriods;
    @Autowired PlatformTransactionManager transactionManager;

    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    private long orgId;
    private long periodId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "m6-race-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                  'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void writerHoldingPeriodLockMakesCloseWaitThenCloseSeesCommittedOpenWriteBoundary() throws Exception {
        var writerLocked = new CountDownLatch(1);
        var allowWriterCommit = new CountDownLatch(1);

        var writer = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            var period = fence.lockOpenById(orgId, periodId);
            writerLocked.countDown();
            try {
                if (!allowWriterCommit.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test writer release timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return period.id();
        }));
        assertThat(writerLocked.await(5, TimeUnit.SECONDS)).isTrue();

        var closer = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            closePeriods.lockOrganizationAdmission(orgId);
            var period = closePeriods.lockPeriod(orgId, periodId);
            return closePeriods.markClosing(orgId, periodId, period.version(),
                    Instant.parse("2026-08-20T04:00:00Z"));
        }));

        assertThatThrownBy(() -> closer.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        allowWriterCommit.countDown();
        assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo(periodId);
        assertThat(closer.get(5, TimeUnit.SECONDS).status()).isEqualTo(BillingPeriodStatus.CLOSING);
    }

    @Test
    void closeWinningMakesLaterKnownPeriodWriterFailClosed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            closePeriods.lockOrganizationAdmission(orgId);
            var period = closePeriods.lockPeriod(orgId, periodId);
            closePeriods.markClosing(orgId, periodId, period.version(),
                    Instant.parse("2026-08-20T04:00:00Z"));
        });

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> fence.lockOpenById(orgId, periodId)))
                .isInstanceOf(DomainException.class);
    }
}
