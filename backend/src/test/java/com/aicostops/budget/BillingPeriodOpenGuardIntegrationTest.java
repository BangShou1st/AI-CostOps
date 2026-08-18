package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BillingPeriodOpenGuard;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BillingPeriod OPEN guard contract: OPEN allows writes, CLOSING and CLOSED
 * reject them, the half-open [start, end) boundaries are exact, and a query
 * with no covering period (or the wrong organization) fails with a
 * state-conflict problem.
 */
@SpringBootTest
@Tag("integration")
class BillingPeriodOpenGuardIntegrationTest extends MySqlContainerSupport {

    private static final String AUG_1 = "2026-08-01 00:00:00.000000";
    private static final String SEP_1 = "2026-09-01 00:00:00.000000";

    @Autowired
    private BillingPeriodOpenGuard guard;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicLong fixture = new AtomicLong();

    private long orgId;
    private long foreignOrgId;

    @BeforeEach
    void setUp() {
        var suffix = fixture.incrementAndGet() + "-" + System.nanoTime();
        orgId = insertOrganization("Period Org " + suffix, "period-" + suffix);
        foreignOrgId = insertOrganization("Period Foreign " + suffix,
                "period-foreign-" + suffix);
    }

    @Test
    void allowsWritesWhenPeriodIsOpen() {
        var periodId = insertPeriod(orgId, AUG_1, SEP_1, "OPEN");
        var result = guard.requireOpen(orgId, instant("2026-08-15T12:00:00Z"));
        assertThat(result.id()).isEqualTo(periodId);
        assertThat(result.status().name()).isEqualTo("OPEN");
    }

    @Test
    void rejectsWritesWhenPeriodIsClosing() {
        insertPeriod(orgId, AUG_1, SEP_1, "CLOSING");
        assertOpenGuardRejected("2026-08-15T12:00:00Z", ProblemCode.PERIOD_NOT_OPEN);
    }

    @Test
    void rejectsWritesWhenPeriodIsClosed() {
        insertPeriod(orgId, AUG_1, SEP_1, "CLOSED");
        assertOpenGuardRejected("2026-08-15T12:00:00Z", ProblemCode.PERIOD_NOT_OPEN);
    }

    @Test
    void periodStartBoundaryIsIncluded() {
        var periodId = insertPeriod(orgId, AUG_1, SEP_1, "OPEN");
        // Exactly at the start boundary: inside the period.
        assertThat(guard.requireOpen(orgId, instant("2026-08-01T00:00:00Z")).id())
                .isEqualTo(periodId);
    }

    @Test
    void periodEndBoundaryIsExcluded() {
        insertPeriod(orgId, AUG_1, SEP_1, "OPEN");
        // Exactly at the end boundary: outside the half-open period.
        assertOpenGuardRejected("2026-09-01T00:00:00Z", ProblemCode.STATE_CONFLICT);
    }

    @Test
    void rejectsWhenNoPeriodCoversTheTime() {
        insertPeriod(orgId, AUG_1, SEP_1, "OPEN");
        assertOpenGuardRejected("2026-10-01T00:00:00Z", ProblemCode.STATE_CONFLICT);
    }

    @Test
    void rejectsWhenThePeriodBelongsToAnotherOrganization() {
        insertPeriod(foreignOrgId, AUG_1, SEP_1, "OPEN");
        // The guard only looks inside the caller's organization: a foreign
        // period is indistinguishable from no covering period and must not
        // leak anything about its existence.
        assertOpenGuardRejected("2026-08-15T12:00:00Z", ProblemCode.STATE_CONFLICT);
    }

    @Test
    void rejectsAmbiguousOverlappingPeriods() {
        insertPeriod(orgId, AUG_1, SEP_1, "OPEN");
        insertPeriod(orgId, "2026-08-15 00:00:00.000000", "2026-09-15 00:00:00.000000", "OPEN");
        // 2026-08-20 is covered by both periods: the guard must fail closed
        // instead of returning a deterministic latest-starting/highest-id
        // winner, because the period identity itself is ambiguous.
        assertOpenGuardRejected("2026-08-20T00:00:00Z", ProblemCode.STATE_CONFLICT);
    }

    @Test
    void rejectsAmbiguousOverlapEvenWhenOneCandidateIsClosed() {
        insertPeriod(orgId, AUG_1, SEP_1, "OPEN");
        insertPeriod(orgId, "2026-08-15 00:00:00.000000", "2026-09-15 00:00:00.000000", "CLOSED");
        // The ambiguity is resolved before any status decision: picking the
        // OPEN candidate because a CLOSED one happens to share the time
        // window would silently legitimize an overlapping period identity.
        assertOpenGuardRejected("2026-08-20T00:00:00Z", ProblemCode.STATE_CONFLICT);
    }

    private void assertOpenGuardRejected(String at, ProblemCode expectedCode) {
        assertThatThrownBy(() -> guard.requireOpen(orgId, instant(at)))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(expectedCode);
                });
    }

    private static Instant instant(String utc) {
        return Instant.parse(utc);
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
}