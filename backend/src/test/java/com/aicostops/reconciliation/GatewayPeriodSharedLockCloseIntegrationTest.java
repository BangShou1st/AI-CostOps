package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BillingPeriodClosePort;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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

/**
 * HC-02 Close ordering against the Gateway shared period lock. Gateway TX1/TX2
 * take {@code FOR SHARE}; Close must still take the exclusive period lock,
 * wait for every in-flight shared holder, and only then commit CLOSING. After
 * that commit a Gateway reader must observe CLOSING and fail closed.
 */
@SpringBootTest
@Tag("integration")
class GatewayPeriodSharedLockCloseIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BillingPeriodClosePort closePeriods;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private long orgId;
    private long periodId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "hc02-close-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
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
    void closeWaitsForGatewaySharedPeriodLockThenMarksClosing() throws Exception {
        var sharedLocked = new CountDownLatch(1);
        var releaseShared = new CountDownLatch(1);

        var gatewayReader = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    lockPeriodShared();
                    sharedLocked.countDown();
                    await(releaseShared, 10_000);
                    return null;
                }));
        assertThat(sharedLocked.await(5, TimeUnit.SECONDS)).isTrue();

        var closer = executor.submit(() -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    closePeriods.lockOrganizationAdmission(orgId);
                    var period = closePeriods.lockPeriod(orgId, periodId);
                    return closePeriods.markClosing(orgId, periodId, period.version(), Instant.now());
                }));

        assertThatThrownBy(() -> closer.get(300, TimeUnit.MILLISECONDS))
                .describedAs("Close must wait for the in-flight Gateway shared period lock")
                .isInstanceOf(TimeoutException.class);

        releaseShared.countDown();
        assertThat(gatewayReader.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(closer.get(5, TimeUnit.SECONDS).status())
                .isEqualTo(BillingPeriodStatus.CLOSING);
    }

    @Test
    void gatewaySharedReaderObservesClosingAfterCloseCommits() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            closePeriods.lockOrganizationAdmission(orgId);
            var period = closePeriods.lockPeriod(orgId, periodId);
            closePeriods.markClosing(orgId, periodId, period.version(), Instant.now());
        });

        var observed = new TransactionTemplate(transactionManager).execute(status ->
                lockPeriodShared());

        assertThat(observed)
                .describedAs("a Gateway TX1/TX2 shared reader must see CLOSING and fail closed")
                .isEqualTo("CLOSING");
    }

    private String lockPeriodShared() {
        return jdbc.queryForObject("""
                SELECT status FROM billing_period WHERE id=? AND org_id=? FOR SHARE
                """, String.class, periodId, orgId);
    }

    private static void await(CountDownLatch latch, long timeoutMillis) {
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
