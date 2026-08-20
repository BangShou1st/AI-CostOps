package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
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
class BillingPeriodFinancialWriteFenceIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired BillingPeriodFinancialWriteFence fence;
    @Autowired PlatformTransactionManager transactionManager;

    private long orgId;
    private long periodId;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        tx = new TransactionTemplate(transactionManager);
        var suffix = "m6-fence-" + System.nanoTime();
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

    @Test
    void locksUniqueOpenPeriodByIdAndEffectiveTime() {
        tx.executeWithoutResult(status -> {
            assertThat(fence.lockOpenById(orgId, periodId).status())
                    .isEqualTo(BillingPeriodStatus.OPEN);
            assertThat(fence.lockOpenAt(orgId, Instant.parse("2026-08-15T00:00:00Z")).id())
                    .isEqualTo(periodId);
        });
    }

    @Test
    void closingAndClosedPeriodsRejectKnownPeriodFinancialWrites() {
        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", periodId);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> fence.lockOpenById(orgId, periodId)))
                .isInstanceOf(DomainException.class);

        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> fence.lockOpenAt(orgId, Instant.parse("2026-08-15T00:00:00Z"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void optionalCoveringFenceAllowsUncoveredTimeButRejectsClosedCoverage() {
        assertThatCode(() -> tx.executeWithoutResult(status ->
                fence.lockIfCoveredAndRequireOpenAt(
                        orgId, Instant.parse("2026-10-01T00:00:00Z"))))
                .doesNotThrowAnyException();

        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                fence.lockIfCoveredAndRequireOpenAt(
                        orgId, Instant.parse("2026-08-15T00:00:00Z"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void organizationAdmissionRejectsAnyClosingPeriodButAllowsClosedHistory() {
        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", periodId);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> fence.lockOrganizationAndRequireNoClosingPeriod(orgId)))
                .isInstanceOf(DomainException.class);

        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);
        tx.executeWithoutResult(status -> {
            fence.lockOrganizationAndRequireNoClosingPeriod(orgId);
            assertThat(fence.hasClosingPeriod(orgId)).isFalse();
        });
    }

    @Test
    void missingOrAmbiguousCoveringPeriodFailsClosed() {
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> fence.lockOpenAt(orgId, Instant.parse("2026-10-01T00:00:00Z"))))
                .isInstanceOf(DomainException.class);

        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-08-10 00:00:00.000000','2026-08-20 00:00:00.000000',
                  'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> fence.lockOpenAt(orgId, Instant.parse("2026-08-15T00:00:00Z"))))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                fence.lockIfCoveredAndRequireOpenAt(
                        orgId, Instant.parse("2026-08-15T00:00:00Z"))))
                .isInstanceOf(DomainException.class);
    }
}
