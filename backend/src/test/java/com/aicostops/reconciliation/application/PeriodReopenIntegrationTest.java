package com.aicostops.reconciliation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.reconciliation.application.PeriodCloseService.ReopenPeriodCommand;
import com.aicostops.reconciliation.domain.PeriodCloseRunStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PeriodReopenIntegrationTest extends AllocationApiTestSupport {

    @Autowired ReconciliationRunService reconciliationRuns;
    @Autowired PeriodCloseService close;

    private long periodId;
    private AuthenticatedUser actor;

    @BeforeEach
    void reopenSetup() {
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_READ','RECONCILIATION_RUN','RECONCILIATION_RESOLVE',
                  'PERIOD_READ','PERIOD_CLOSE','PERIOD_REOPEN')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        actor = new AuthenticatedUser(actorUserId, 7);
    }

    @Test
    void reopenIncrementsGenerationOnceAndPreservesCloseHistory() {
        reconciliationRuns.run(actor, periodId);
        var closed = close.close(actor, periodId);
        var closeRunId = closed.run().id();
        var oldClosedAt = closed.period().closedAt();
        var oldChecks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_close_check WHERE period_close_run_id=?",
                Long.class, closeRunId);
        var ledgerRowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId);
        var reconciliationRowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE org_id=? AND billing_period_id=?",
                Long.class, orgId, periodId);

        var reopened = close.reopen(actor, periodId,
                new ReopenPeriodCommand("LATE_PROVIDER_DATA", "Provider supplied late January evidence."));

        assertThat(reopened.period().status().name()).isEqualTo("OPEN");
        assertThat(reopened.period().closeGeneration()).isEqualTo(1);
        assertThat(reopened.period().closedAt()).isEqualTo(oldClosedAt);
        assertThat(reopened.period().reopenedAt()).isNotNull();
        assertThat(reopened.run().id()).isEqualTo(closeRunId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_close_check WHERE period_close_run_id=?",
                Long.class, closeRunId)).isEqualTo(oldChecks);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isEqualTo(ledgerRowsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run WHERE org_id=? AND billing_period_id=?",
                Long.class, orgId, periodId)).isEqualTo(reconciliationRowsBefore);

        // Reconciliation, close, and reopen each leave a durable audit row.
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE org_id=? AND event_type='RECONCILIATION_RUN_COMPLETED'
                        """, Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE org_id=? AND event_type='PERIOD_CLOSED'
                        """, Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_event
                        WHERE org_id=? AND event_type='PERIOD_REOPENED'
                        """, Integer.class, orgId)).isEqualTo(1);
        var auditJson = jdbc.queryForObject("""
                SELECT metadata_json FROM audit_event
                WHERE org_id=? AND event_type='PERIOD_REOPENED'
                ORDER BY id DESC LIMIT 1
                """, String.class, orgId);
        assertThat(auditJson).contains("LATE_PROVIDER_DATA").contains("late January evidence");
    }

    @Test
    void secondReopenWhileOpenIsRejectedWithoutGenerationIncrement() {
        reconciliationRuns.run(actor, periodId);
        close.close(actor, periodId);
        close.reopen(actor, periodId, new ReopenPeriodCommand("CORRECTION", "Need another cycle."));

        assertThatThrownBy(() -> close.reopen(actor, periodId,
                new ReopenPeriodCommand("CORRECTION", "Second reopen should fail.")))
                .isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject(
                "SELECT close_generation FROM billing_period WHERE id=?", Long.class, periodId))
                .isEqualTo(1);
    }

    @Test
    void nextCloseAfterReopenStartsAttemptOneOfNewGeneration() {
        reconciliationRuns.run(actor, periodId);
        var firstClose = close.close(actor, periodId);
        close.reopen(actor, periodId, new ReopenPeriodCommand("LATE_DATA", "Reopen for late data."));

        var secondClose = close.close(actor, periodId);
        assertThat(firstClose.run().closeGeneration()).isZero();
        assertThat(secondClose.run().closeGeneration()).isEqualTo(1);
        assertThat(secondClose.run().attemptNo()).isEqualTo(1);
        assertThat(secondClose.run().status()).isEqualTo(PeriodCloseRunStatus.CLOSED);
    }
}
