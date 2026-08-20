package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Two real transactions converge on one immutable stable-key posting. */
@SpringBootTest
@Tag("integration")
class ProviderPostingConcurrencyIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ProviderChargePostingService postings;

    private long chargeId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code='LEDGER_POST'
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        chargeId = insertCharge("10.00000000");
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, chargeId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '10.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, projectId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
    }

    @Test
    void concurrentDuplicatePostsCreateOnePostingAndOneEntry() throws Exception {
        var start = new CountDownLatch(1);
        var actor = new AuthenticatedUser(actorUserId, 7);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> postAfter(start, actor));
            var second = pool.submit(() -> postAfter(start, actor));
            start.countDown();
            var firstResult = first.get(30, TimeUnit.SECONDS);
            var secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(secondResult.posting().id()).isEqualTo(firstResult.posting().id());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                    Integer.class, orgId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                    Integer.class, orgId)).isEqualTo(1);
            assertThat(auditCount("LEDGER_CHARGE_POSTED")).isEqualTo(1);
        }
    }

    private com.aicostops.ledger.application.LedgerReadModels.LedgerPostingDetail postAfter(
            CountDownLatch start, AuthenticatedUser actor) throws InterruptedException {
        start.await();
        return postings.post(actor, chargeId, new PostSourceCommand(List.of()));
    }
}
