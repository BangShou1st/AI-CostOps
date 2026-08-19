package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.LedgerQueryService;
import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingDetail;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Scoped list/detail visibility and provider lineage projection tests. */
@SpringBootTest
@Tag("integration")
class LedgerQueryIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ProviderChargePostingService postings;
    @Autowired
    private LedgerQueryService queries;

    private long chargeId;
    private long decisionId;
    private long firstEntryId;
    private long secondEntryId;
    private LedgerPostingDetail posted;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('LEDGER_POST','LEDGER_READ')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        insertPeriod();
        chargeId = insertCharge("10.00000000");
        decisionId = insertConfirmedDecision();
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        posted = postings.post(new AuthenticatedUser(actorUserId, 7), chargeId,
                new PostSourceCommand(java.util.List.of()));
        firstEntryId = posted.entries().get(0).id();
        secondEntryId = posted.entries().get(1).id();
    }

    @Test
    void orgScopeListsEntriesAndVisibleTotalsUseTheFilteredRows() {
        var page = queries.listPostings(new AuthenticatedUser(actorUserId, 7),
                PageRequest.defaults(), null, null, projectId, null, null, "postedAt,desc");

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).visibleEntries()).extracting(e -> e.id())
                .containsExactly(firstEntryId);
        assertThat(page.items().get(0).visibleEntries().get(0).amount())
                .isEqualByComparingTo("6.00000000");

        var entry = queries.getEntry(new AuthenticatedUser(actorUserId, 7), firstEntryId);
        assertThat(entry.lineage().chargeFactId()).isEqualTo(chargeId);
        assertThat(entry.lineage().rawProviderRecordId()).isEqualTo(rawRecordId);
        assertThat(entry.lineage().importAttemptId()).isPositive();
        assertThat(entry.lineage().importBatchId()).isPositive();
        assertThat(entry.lineage().providerEvidenceId()).isPositive();
        assertThat(entry.lineage().expenseClaimId()).isNull();
    }

    @Test
    void typedScopeSeesOnlyMatchingTargetAndHidesOtherEntry() {
        revokeAllAssignments();
        createPermissionRole("ALLOC_READER", java.util.List.of("LEDGER_READ"));
        assign("ALLOC_READER", "PROJECT", projectId);

        var user = new AuthenticatedUser(actorUserId, 7);
        var page = queries.listPostings(user, PageRequest.defaults(), null, null,
                null, null, null, "postedAt,desc");
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).visibleEntries()).extracting(e -> e.id())
                .containsExactly(firstEntryId);
        assertThatThrownBy(() -> queries.getEntry(user, secondEntryId))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("granted scope");
    }

    @Test
    void missingLedgerReadGrantIsForbiddenBeforeLedgerDisclosure() {
        revokeAllAssignments();

        assertThatThrownBy(() -> queries.listPostings(new AuthenticatedUser(actorUserId, 7),
                PageRequest.defaults(), null, null, null, null, null, "postedAt,desc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("required permission");
    }

    private void insertPeriod() {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
    }

    private long insertConfirmedDecision() {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var id = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, chargeId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '6.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, id, projectId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 1, '4.00000000','CNY', NULL, NULL, ?, UTC_TIMESTAMP(6))
                """, orgId, id, teamId);
        return id;
    }
}
