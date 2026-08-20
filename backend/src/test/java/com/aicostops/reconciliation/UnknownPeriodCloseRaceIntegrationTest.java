package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BillingPeriodClosePort;
import com.aicostops.ingestion.application.ImportCloseBlockerPort;
import com.aicostops.ingestion.application.ImportCloseAdmissionPort;
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
class UnknownPeriodCloseRaceIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired ImportCloseAdmissionPort importAdmission;
    @Autowired ImportCloseBlockerPort importBlocker;
    @Autowired BillingPeriodClosePort closePeriods;
    @Autowired PlatformTransactionManager transactionManager;

    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    private long orgId;
    private long periodId;
    private long memberId;
    private long providerAccountId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "m6-org-race-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,status,created_at,updated_at)
                VALUES (?,'OPENAI',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix);
        providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
    void unknownPeriodAdmissionWinningMakesCloseWaitOnOrganizationRow() throws Exception {
        var admissionLocked = new CountDownLatch(1);
        var allowAdmissionCommit = new CountDownLatch(1);
        var admitted = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            importAdmission.lockAndRequireNoClosingPeriod(orgId);
            var batchId = insertUnknownImportBatch();
            admissionLocked.countDown();
            try {
                if (!allowAdmissionCommit.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test admission release timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return batchId;
        }));
        assertThat(admissionLocked.await(5, TimeUnit.SECONDS)).isTrue();

        var closer = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            closePeriods.lockOrganizationAdmission(orgId);
            var period = closePeriods.lockPeriod(orgId, periodId);
            closePeriods.markClosing(orgId, periodId, period.version(),
                    Instant.parse("2026-08-20T04:00:00Z"));
            return Boolean.TRUE;
        }));

        assertThatThrownBy(() -> closer.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        allowAdmissionCommit.countDown();
        var batchId = admitted.get(5, TimeUnit.SECONDS);
        assertThat(batchId).isPositive();
        assertThat(closer.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(importBlocker.countOpenImports(orgId,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")))
                .isEqualTo(1);
    }

    @Test
    void closeWinningRejectsLaterUnknownPeriodAdmission() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            closePeriods.lockOrganizationAdmission(orgId);
            var period = closePeriods.lockPeriod(orgId, periodId);
            closePeriods.markClosing(orgId, periodId, period.version(),
                    Instant.parse("2026-08-20T04:00:00Z"));
        });

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> importAdmission.lockAndRequireNoClosingPeriod(orgId)))
                .isInstanceOf(DomainException.class);
    }

    private long insertUnknownImportBatch() {
        var sha = String.format("%064d", Math.abs(System.nanoTime()));
        jdbc.update("""
                INSERT INTO evidence(
                  org_id,sha256,object_key,original_filename,media_type,size_bytes,
                  uploaded_by_member_id,storage_status,created_at,updated_at)
                VALUES (?,?,'race/unknown','unknown.csv','text/csv',1,?,'AVAILABLE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha, memberId);
        var evidenceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO import_batch(
                  org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                  parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,'OPENAI','FILE_EXPORT','race-v1','PENDING',NULL,NULL,?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, providerAccountId, memberId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
