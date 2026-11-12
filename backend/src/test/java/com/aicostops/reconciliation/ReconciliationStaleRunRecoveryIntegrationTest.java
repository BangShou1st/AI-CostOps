package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.reconciliation.application.ReconciliationRunService;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class ReconciliationStaleRunRecoveryIntegrationTest extends MySqlContainerSupport {

    private static final String ALGORITHM_VERSION = "M6_PERIOD_PROVIDER_CURRENCY_V1";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationMapper reconciliation;
    @Autowired ReconciliationRunService runService;

    private long orgId;
    private long userId;
    private long memberId;
    private long periodId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "recon-stale-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.test", suffix);
        userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
    }

    @Test
    void convergesOnlyRunsOlderThanTheStaleTimeoutAndAuditsThem() {
        var now = Instant.now();
        var staleStartedAt = now.minus(Duration.ofHours(2));
        reconciliation.insertRun(orgId, periodId, "RUNNING", ALGORITHM_VERSION,
                new BigDecimal("0.00000000"), "{}", memberId, staleStartedAt,
                staleStartedAt, staleStartedAt);
        var staleRunId = reconciliation.lastInsertId();
        reconciliation.insertRun(orgId, periodId, "RUNNING", ALGORITHM_VERSION,
                new BigDecimal("0.00000000"), "{}", memberId, now, now, now);
        var freshRunId = reconciliation.lastInsertId();

        assertThat(runService.failStaleRunningRuns(Duration.ofMinutes(30), 100)).isEqualTo(1);

        var stale = reconciliation.selectRunByIdAndOrganization(orgId, staleRunId);
        assertThat(stale.status()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(stale.errorCode()).isEqualTo("RECONCILIATION_RUN_ABANDONED");
        assertThat(stale.finishedAt()).isNotNull();

        var fresh = reconciliation.selectRunByIdAndOrganization(orgId, freshRunId);
        assertThat(fresh.status()).isEqualTo(ReconciliationRunStatus.RUNNING);
        assertThat(fresh.errorCode()).isNull();
        assertThat(fresh.finishedAt()).isNull();

        var audit = jdbc.queryForMap("""
                SELECT actor_user_id AS actorUserId,subject_type AS subjectType,
                       subject_id AS subjectId
                FROM audit_event
                WHERE org_id=? AND event_type='RECONCILIATION_RUN_FAILED' AND subject_id=?
                """, orgId, staleRunId);
        assertThat(audit.get("actorUserId")).isEqualTo(userId);
        assertThat(audit.get("subjectType")).isEqualTo("RECONCILIATION_RUN");
        assertThat(audit.get("subjectId")).isEqualTo(staleRunId);

        assertThat(runService.failStaleRunningRuns(Duration.ofMinutes(30), 100)).isZero();
    }
}
