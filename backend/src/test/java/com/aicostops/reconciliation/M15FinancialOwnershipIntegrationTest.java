package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargeHybridPostingGuard;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.reconciliation.application.HybridReconciliationActionService;
import com.aicostops.reconciliation.application.HybridReconciliationActionService.ChargeDispositionCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * M15 statement Charge financial ownership: one real Provider statement Charge
 * owns exactly one financial representation forever — either a normal
 * PROVIDER_CHARGE Ledger posting (DIRECT_PROVIDER_CHARGE) or reconciliation
 * evidence consumed by exactly one Gateway financial resolution
 * (RECONCILIATION_EVIDENCE). The Charge row is the shared serialization point
 * of the posting fence, the Gateway statement resolution and the disposition
 * command, and the database unique constraint is the last line of defense.
 */
@SpringBootTest
@Tag("integration")
@TestPropertySource(properties = {
        "aicostops.reconciliation.correlation-certified-profiles"
                + "=GLM:FILE_EXPORT:TEST-PARSER-V1"})
class M15FinancialOwnershipIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired GatewayFinancialResolutionService resolutions;
    @Autowired HybridReconciliationActionService hybridActions;
    @Autowired ProviderChargePostingService postings;
    @Autowired ProviderChargeHybridPostingGuard hybridGuard;

    private final ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
    private AuthenticatedUser actor;
    private long periodId;
    private long runId;
    private long budgetId;

    @AfterEach
    void stopExecutor() {
        raceExecutor.shutdownNow();
    }

    @BeforeEach
    void ownershipSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'LEDGER_POST','LEDGER_CORRECT','RECONCILIATION_RESOLVE')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        runId = insertCompletedRun();
        budgetId = insertBudget();
    }

    // ------------------------------------------------------------------
    // Group A: charge financial ownership
    // ------------------------------------------------------------------

    @Test
    void providerPostedChargeCannotBeStatementResolved() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        // The charge was already posted through the normal V1 provider path.
        seedPostedProviderCharge(chargeId, "2.00000000");

        assertThatThrownBy(() -> resolveStatement(fixture.requestId(), chargeId, "own-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("posted");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isZero();
    }

    @Test
    void directDispositionChargeCannotBeStatementResolved() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        jdbc.update("""
                INSERT INTO provider_charge_disposition(
                  org_id,charge_fact_id,disposition,decision_source,decided_by_member_id,
                  reason_code,resolution_note,created_at)
                VALUES (?,?, 'DIRECT_PROVIDER_CHARGE','MANUAL',?,'MANUAL_DIRECT',
                  'Reviewed direct provider cost',UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);

        assertThatThrownBy(() -> resolveStatement(fixture.requestId(), chargeId, "own-2"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("DIRECT_PROVIDER_CHARGE");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void statementResolvedChargeBecomesReconciliationEvidenceAndCanNeverBecomeDirect() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");

        var result = resolveStatement(fixture.requestId(), chargeId, "own-3");
        assertThat(result.adjustmentId()).isNotNull();

        // The statement resolution atomically claims the Charge as
        // RECONCILIATION_EVIDENCE in the same transaction.
        var disposition = jdbc.queryForMap(
                "SELECT disposition,decision_source,reconciliation_run_id "
                        + "FROM provider_charge_disposition WHERE org_id=? AND charge_fact_id=?",
                orgId, chargeId);
        assertThat(disposition.get("disposition")).isEqualTo("RECONCILIATION_EVIDENCE");
        assertThat(disposition.get("decision_source")).isEqualTo("MANUAL");
        assertThat(((Number) disposition.get("reconciliation_run_id")).longValue())
                .isEqualTo(runId);

        // A later DIRECT disposition can never steal the Charge back.
        assertThatThrownBy(() -> hybridActions.decideChargeDisposition(actor,
                insertCaseFor(fixture.providerAccountId()),
                new ChargeDispositionCommand(chargeId, "DIRECT_PROVIDER_CHARGE",
                        "LATE_DIRECT", "Trying to reclaim the charge"),
                "own-disp-1"))
                .isInstanceOf(DomainException.class);
        // A second disposition of any kind is refused: the ownership row exists.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, chargeId)).isEqualTo(1L);
    }

    @Test
    void exactStatementResolutionClaimsChargeAsSystemExactEvidence() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertExactEvidence(chargeId, fixture.requestId(), fixture.attemptId(),
                fixture.providerAccountId());

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", null, null, null,
                        "REVIEWED_EXACT_LINE", "Exact correlation reviewed"),
                "own-exact-1");

        // The server derives the bound charge from the exact evidence.
        assertThat(((Number) jdbc.queryForObject(
                "SELECT statement_charge_fact_id FROM gateway_financial_resolution WHERE id=?",
                Long.class, result.resolutionId())).longValue()).isEqualTo(chargeId);
        var disposition = jdbc.queryForMap(
                "SELECT disposition,decision_source,decided_by_member_id "
                        + "FROM provider_charge_disposition WHERE org_id=? AND charge_fact_id=?",
                orgId, chargeId);
        assertThat(disposition.get("disposition")).isEqualTo("RECONCILIATION_EVIDENCE");
        assertThat(disposition.get("decision_source")).isEqualTo("SYSTEM_EXACT");
        assertThat(disposition.get("decided_by_member_id")).isNull();
    }

    @Test
    void exactCorrelationSurvivesIdsBeyondTheLongAutoboxCache() {
        // Boxed id reference equality (`==`/`!=`) silently breaks for values
        // outside the Long autobox cache (>127): a full integration suite run
        // pushed gateway_route_attempt ids past 127 and made the exact
        // correlation reject evidence that numerically matches. The regression
        // is reproduced deterministically by pushing the auto-increment past
        // the cache boundary before resolving.
        var base = insertGatewayFixture("UNKNOWN", false);
        var filler = new java.util.ArrayList<Object[]>(150);
        for (int attemptNo = 2; attemptNo <= 151; attemptNo++) {
            filler.add(new Object[]{orgId, base.requestId(), attemptNo, fixedRequestId(),
                    base.providerAccountId(), base.providerModelId(),
                    base.pricingVersionId()});
        }
        jdbc.batchUpdate("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,
                  route_decision_id,provider_account_id,provider_model_id,pricing_version_id,
                  status,created_at)
                VALUES (?,?,?,?,?,?,?,'PLANNED',UTC_TIMESTAMP(6))
                """, filler);

        // A sibling fixture created after the filler receives a route attempt
        // id above the Long autobox cache.
        var fixture = insertSiblingGatewayFixture(base);
        assertThat(fixture.attemptId()).isGreaterThan(127L);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertExactEvidence(chargeId, fixture.requestId(), fixture.attemptId(),
                fixture.providerAccountId());

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", null, null, null,
                        "REVIEWED_EXACT_LINE", "Exact correlation reviewed"),
                "own-exact-128");

        assertThat(((Number) jdbc.queryForObject(
                "SELECT statement_charge_fact_id FROM gateway_financial_resolution WHERE id=?",
                Long.class, result.resolutionId())).longValue()).isEqualTo(chargeId);
    }

    @Test
    void postingGuardRejectsChargeConsumedByGatewayFinancialResolution() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        // A committed gateway resolution consumed this charge. The attempt is
        // demoted to PLANNED so no durable billable overlap exists here: only
        // the gateway financial ownership can block the posting.
        jdbc.update("UPDATE gateway_route_attempt SET status='PLANNED' WHERE id=?",
                fixture.attemptId());
        jdbc.update("""
                INSERT INTO reconciliation_adjustment(
                    org_id,reconciliation_run_id,adjustment_key,adjustment_scope,
                    provider_account_id,currency,amount,adjustment_period_id,
                    gateway_request_id,gateway_route_attempt_id,statement_charge_fact_id,
                    created_by_member_id,reason_code,reason_note,created_at)
                VALUES (?,?,?,'GATEWAY_REQUEST',?,'USD','2.00000000',?,?,?,?,?,
                  'REVIEWED_LINE','Reviewed statement line',UTC_TIMESTAMP(6))
                """, orgId, runId, "ADJ:OWN-GUARD", fixture.providerAccountId(), periodId,
                fixture.requestId(), fixture.attemptId(), chargeId, actorMemberId);
        var adjustmentId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_adjustment WHERE org_id=? AND adjustment_key=?",
                Long.class, orgId, "ADJ:OWN-GUARD");
        jdbc.update("""
                INSERT INTO gateway_financial_resolution(
                    org_id,reconciliation_run_id,request_id,route_attempt_id,
                    statement_charge_fact_id,reconciliation_adjustment_id,resolution_type,
                    reservation_outcome,resolved_by_member_id,reason_code,reason_note,
                    resolved_at,created_at)
                VALUES (?,?,?,?,?,?,'STATEMENT_ADJUSTMENT_POSTED','NONE',?,?,
                  'Reviewed statement line',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, fixture.requestId(), fixture.attemptId(), chargeId,
                adjustmentId, actorMemberId, "REVIEWED_LINE");

        // No durable billable attempt overlaps the charge scope here, so only
        // the gateway financial ownership can block the posting.
        var decision = hybridGuard.checkHybridPostingEligibility(orgId, chargeId, periodId,
                "USD");
        assertThat(decision.outcome()).isEqualTo(
                ProviderChargeHybridPostingGuard.HybridPostingOutcome
                        .BLOCKED_RECONCILIATION_EVIDENCE);
    }

    @Test
    void sameChargeTwoRequestsConcurrentResolutionProducesExactlyOneOwner() throws Exception {
        var first = insertGatewayFixture("UNKNOWN", false);
        var second = insertSiblingGatewayFixture(first);
        insertUnresolvedEvidence(first.requestId(), first.attemptId(), first.usageFactId(), null);
        insertUnresolvedEvidence(second.requestId(), second.attemptId(), second.usageFactId(),
                null);
        var chargeId = insertConfirmedStatementCharge(first.providerAccountId(), "2.00000000");

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var firstFailure = new AtomicReference<Throwable>();
        var secondFailure = new AtomicReference<Throwable>();
        var firstFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return resolveStatement(first.requestId(), chargeId, "own-race-a");
            } catch (Throwable failure) {
                firstFailure.set(failure);
                return null;
            }
        });
        var secondFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return resolveStatement(second.requestId(), chargeId, "own-race-b");
            } catch (Throwable failure) {
                secondFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        firstFuture.get(60, TimeUnit.SECONDS);
        secondFuture.get(60, TimeUnit.SECONDS);

        var successes = (firstFailure.get() == null ? 1 : 0)
                + (secondFailure.get() == null ? 1 : 0);
        assertThat(successes).isEqualTo(1);
        var loser = firstFailure.get() != null ? firstFailure.get() : secondFailure.get();
        assertThat(loser).isInstanceOf(DomainException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? "
                        + "AND charge_fact_id=? AND disposition='RECONCILIATION_EVIDENCE'",
                Long.class, orgId, chargeId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE org_id=? AND event_type=?",
                Long.class, orgId, "GATEWAY_FINANCIAL_RESOLVED")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                budgetId)).isEqualByComparingTo("2.00000000");
    }

    @Test
    void hybridOverlapBlocksNormalProviderPostingWhileStatementResolutionClaimsCharge() throws Exception {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        makeChargePostable(chargeId, "2.00000000");

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var postingFailure = new AtomicReference<Throwable>();
        var resolutionFailure = new AtomicReference<Throwable>();
        var postingFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return postings.post(actor, chargeId, new PostSourceCommand(List.of()));
            } catch (Throwable failure) {
                postingFailure.set(failure);
                return null;
            }
        });
        var resolutionFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return resolveStatement(fixture.requestId(), chargeId, "own-race-post");
            } catch (Throwable failure) {
                resolutionFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        postingFuture.get(60, TimeUnit.SECONDS);
        resolutionFuture.get(60, TimeUnit.SECONDS);

        // The fixture holds a durable possible-billable Gateway overlap in the
        // same scope/period, so "the normal provider posting wins" is not a
        // reachable terminal state: the Hybrid fence blocks the posting and the
        // reviewed statement resolution claims the Charge exactly once.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='PROVIDER_CHARGE' AND status='POSTED'",
                Long.class, orgId)).isZero();
        assertThat(postingFailure.get()).isInstanceOf(DomainException.class);
        assertThat(resolutionFailure.get()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=? "
                        + "AND statement_charge_fact_id=?",
                Long.class, orgId, chargeId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? "
                        + "AND charge_fact_id=? AND disposition='RECONCILIATION_EVIDENCE'",
                Long.class, orgId, chargeId)).isEqualTo(1L);
        // The charge is represented financially exactly once.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isEqualTo(1L);
    }

    @Test
    void postedProviderChargeCannotBeManuallyClassifiedReconciliationEvidence() {
        var chargeId = insertConfirmedStatementCharge(accountId, "2.00000000");
        seedPostedProviderCharge(chargeId, "2.00000000");
        var caseId = insertCaseFor(accountId);

        assertThatThrownBy(() -> hybridActions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(chargeId, "RECONCILIATION_EVIDENCE",
                        "MANUAL_REVIEW", "Trying to reclassify a posted charge"),
                "own-posted-disp-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("posted");

        // The contradictory terminal state (POSTED provider charge +
        // RECONCILIATION_EVIDENCE ownership) never exists.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? "
                        + "AND charge_fact_id=?",
                Long.class, orgId, chargeId)).isZero();
    }

    @Test
    void postedProviderChargeKeepsLegacyCompatibleDirectClaimExactlyOnce() {
        var chargeId = insertConfirmedStatementCharge(accountId, "2.00000000");
        seedPostedProviderCharge(chargeId, "2.00000000");
        var caseId = insertCaseFor(accountId);

        // Recording the direct ownership of an already-posted charge is the
        // legacy-compatible claim (V23 LEGACY_POSTED semantics) and is allowed
        // exactly once.
        hybridActions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(chargeId, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Legacy posted charge"), "own-posted-direct-1");

        assertThat(jdbc.queryForObject(
                "SELECT disposition FROM provider_charge_disposition WHERE org_id=? "
                        + "AND charge_fact_id=?",
                String.class, orgId, chargeId)).isEqualTo("DIRECT_PROVIDER_CHARGE");
        assertThatThrownBy(() -> hybridActions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(chargeId, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Second claim"), "own-posted-direct-2"))
                .isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? "
                        + "AND charge_fact_id=?",
                Long.class, orgId, chargeId)).isEqualTo(1L);
    }

    @Test
    void providerPostingVsDispositionRaceNeverCreatesContradictoryOwnership() throws Exception {
        var chargeId = insertConfirmedStatementCharge(accountId, "2.00000000");
        makeChargePostable(chargeId, "2.00000000");
        var caseId = insertCaseFor(accountId);

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var postingFailure = new AtomicReference<Throwable>();
        var dispositionFailure = new AtomicReference<Throwable>();
        var postingFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return postings.post(actor, chargeId, new PostSourceCommand(List.of()));
            } catch (Throwable failure) {
                postingFailure.set(failure);
                return null;
            }
        });
        var dispositionFuture = raceExecutor.submit(() -> {
            ready.countDown();
            release.await();
            try {
                return hybridActions.decideChargeDisposition(actor, caseId,
                        new ChargeDispositionCommand(chargeId, "RECONCILIATION_EVIDENCE",
                                "MANUAL_REVIEW", "Racing reclassification"),
                        "own-race-disp");
            } catch (Throwable failure) {
                dispositionFailure.set(failure);
                return null;
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        postingFuture.get(60, TimeUnit.SECONDS);
        dispositionFuture.get(60, TimeUnit.SECONDS);

        var providerPostings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='PROVIDER_CHARGE' AND status='POSTED'",
                Long.class, orgId);
        var reconciliationDispositions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? "
                        + "AND charge_fact_id=? AND disposition='RECONCILIATION_EVIDENCE'",
                Long.class, orgId, chargeId);

        // posted PROVIDER_CHARGE + RECONCILIATION_EVIDENCE ownership is never
        // the terminal state, whichever thread wins the charge lock.
        if (providerPostings == 1) {
            assertThat(postingFailure.get()).isNull();
            assertThat(dispositionFailure.get()).isInstanceOf(DomainException.class);
            assertThat(reconciliationDispositions).isZero();
        } else {
            assertThat(providerPostings).isZero();
            assertThat(reconciliationDispositions).isEqualTo(1L);
            assertThat(dispositionFailure.get()).isNull();
            assertThat(postingFailure.get()).isInstanceOf(DomainException.class);
        }
    }

    // ------------------------------------------------------------------
    // Group C: run evidence freshness / attempt ownership
    // ------------------------------------------------------------------

    @Test
    void requestCreatedAfterRunCannotBindStatementManually() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        // The request was created after the run completed: no reviewed evidence
        // for it exists in this run.

        assertThatThrownBy(() -> resolveStatement(fixture.requestId(), chargeId, "own-after-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("GATEWAY_UNRESOLVED");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void failoverToNewAttemptMakesOldRunEvidenceStale() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(), fixture.usageFactId(),
                null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        // Failover: append attempt B and repoint the request at it.
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                SELECT org_id,?,2,?,provider_account_id,provider_model_id,pricing_version_id,
                  'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6)
                FROM gateway_route_attempt WHERE id=?
                """, fixture.requestId(), fixedRequestId(), fixture.attemptId());
        var attemptB = jdbc.queryForObject(
                "SELECT id FROM gateway_route_attempt WHERE request_id=? AND attempt_no=2",
                Long.class, fixture.requestId());
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptB, fixture.requestId());

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "own-failover-proof-1", null,
                        "PROVIDER_PORTAL_CONFIRMED_NO_CHARGE",
                        "Provider confirmed no charge"),
                "own-failover-nc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("stale");
        assertThatThrownBy(() -> resolveStatement(fixture.requestId(), chargeId,
                "own-failover-st"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("stale");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void clientCannotDeclareTheBindingClassification() {
        var fixture = insertGatewayFixture("UNKNOWN", false);
        insertUnresolvedEvidence(fixture.requestId(), fixture.attemptId(),
                fixture.usageFactId(), null);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "MANUAL_BINDING", "Client-declared binding"),
                "own-lie-1"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "EXACT_PROVIDER_REQUEST", "Client-declared exact"),
                "own-lie-2"))
                .isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private record OwnershipFixture(long requestId, long attemptId, Long usageFactId,
            long providerAccountId, long providerModelId, long pricingVersionId) {
    }

    private GatewayResolutionServiceResult resolveStatement(long requestId, long chargeId,
            String key) {
        return new GatewayResolutionServiceResult(resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, requestId,
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"),
                key));
    }

    private record GatewayResolutionServiceResult(long resolutionId, Long adjustmentId) {
        private GatewayResolutionServiceResult(
                com.aicostops.reconciliation.application.GatewayFinancialResolutionService
                        .GatewayResolutionResult result) {
            this(result.resolutionId(), result.adjustmentId());
        }
    }

    private long insertCompletedRun() {
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, periodId, "e".repeat(64), actorMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? AND billing_period_id=? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, orgId, periodId);
    }

    private long insertBudget() {
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertUnresolvedEvidence(long requestId, long attemptId, Long usageFactId,
            Long settlementId) {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(org_id,reconciliation_run_id,evidence_key,
                  provider_account_id,currency,match_kind,gateway_request_id,
                  gateway_route_attempt_id,gateway_usage_fact_id,gateway_settlement_id,created_at)
                VALUES (?,?,CONCAT('GATEWAY_UNRESOLVED:REQUEST:',?),?,'USD','GATEWAY_UNRESOLVED',
                  ?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, requestId, accountId, requestId, attemptId, usageFactId,
                settlementId);
    }

    private void insertExactEvidence(long chargeId, long requestId, long attemptId,
            long providerAccountId) {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(org_id,reconciliation_run_id,evidence_key,
                  provider_account_id,currency,match_kind,charge_fact_id,gateway_request_id,
                  gateway_route_attempt_id,provider_request_id,created_at)
                VALUES (?,?,CONCAT('EXACT:CHARGE:',?,':REQUEST:',?),?,'USD',
                  'EXACT_PROVIDER_REQUEST',?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, chargeId, requestId, providerAccountId, chargeId, requestId,
                attemptId, "own-req-" + requestId);
    }

    private void seedPostedProviderCharge(long chargeId, String amount) {
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,
                    billing_period_id,status,posting_actor_type,posted_by_member_id,posted_at,
                    created_at)
                VALUES (?,?,'PROVIDER_CHARGE',?,NULL,?,'POSTED','MEMBER',?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "CHARGE:" + chargeId + ":OWN", chargeId, periodId, actorMemberId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_charge_fact_id,created_at)
                VALUES (?,?,0,'COST',?,'USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, amount, projectId, chargeId);
    }

    private void makeChargePostable(long chargeId, String amount) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?,
                  UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, chargeId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, ?,'USD', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, amount, projectId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
    }

    private long insertCaseFor(long providerAccountId) {
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','2.00000000','0.00000000','2.00000000',
                  1,1,'INVESTIGATING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, providerAccountId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertConfirmedStatementCharge(long providerAccountId, String amount) {
        var rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, providerAccountId,
                "own" + UUID.randomUUID().toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,0,'GLM','USAGE',?,'USD',?,DATE_ADD(?, INTERVAL 1 DAY),'CLEAN',
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, AUG_START, AUG_START);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgId, rawRecordId);
    }

    private OwnershipFixture insertGatewayFixture(String usageStatus, boolean withBudget) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "own-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "own-model-" + suffix, suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var providerCode = "MIMO-" + suffix.substring(0, 10);
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, suffix, "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "own-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, accountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  budget_enforcement_mode,status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(61), serviceId, projectId, projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixedRequestId(), credentialId, serviceId, projectId, projectId,
                modelId, digest(62), digest(63), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?, 'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), accountId, providerModelId,
                pricingVersionId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        Long usageFactId = null;
        if (usageStatus != null) {
            jdbc.update("""
                    INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                      status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                      currency,observed_at,created_at)
                    VALUES (?,?,?,1,?,UTC_TIMESTAMP(6),
                      'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),
                      UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, usageStatus, pricingVersionId);
            usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                    usageFactId, requestId);
        }
        if (withBudget) {
            insertBudget();
        }
        return new OwnershipFixture(requestId, attemptId, usageFactId, accountId, providerModelId,
                pricingVersionId);
    }

    private OwnershipFixture insertSiblingGatewayFixture(OwnershipFixture base) {
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                SELECT org_id,?,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,?,?,1,'TRANSPORT_COMPLETED',billing_period_id,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
                FROM gateway_request WHERE id=?
                """, fixedRequestId(), digest(64), digest(65), base.requestId());
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                SELECT org_id,?,1,?,provider_account_id,provider_model_id,pricing_version_id,
                  'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6)
                FROM gateway_route_attempt WHERE id=?
                """, requestId, fixedRequestId(), base.attemptId());
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                  status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                  currency,observed_at,created_at)
                VALUES (?,?,?,1,'UNKNOWN',UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, requestId, attemptId, base.pricingVersionId());
        var usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                usageFactId, requestId);
        return new OwnershipFixture(requestId, attemptId, usageFactId, base.providerAccountId(),
                base.providerModelId(), base.pricingVersionId());
    }

    private static String fixedRequestId() {
        return (UUID.randomUUID().toString().replace("-", "")
                + "0000000000000000000000000000").substring(0, 40);
    }

    private static byte[] digest(int seed) {
        var result = new byte[32];
        for (var i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }
}
