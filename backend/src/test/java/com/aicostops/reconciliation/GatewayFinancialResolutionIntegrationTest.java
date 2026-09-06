package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M15 gateway financial resolution: reviewed terminal financial decisions for
 * possible-billable requests with missing/INCOMPLETE/UNKNOWN usage or a
 * RECONCILIATION_REQUIRED Settlement, never competing with the normal M13
 * settlement path and always terminal against later M13 settlement attempts.
 *
 * <p>Financial safety rules proven here: run/case/request lineage, a
 * server-derived statement adjustment amount bound to one authoritative
 * statement Charge, manual reviewed bindings, positive no-charge proof and the
 * run's GATEWAY_UNRESOLVED evidence requirement.
 */
@SpringBootTest
@Tag("integration")
class GatewayFinancialResolutionIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";
    private static final String NO_CHARGE_PROOF = "PROVIDER_PORTAL_CONFIRMED_NO_CHARGE";

    @Autowired JdbcTemplate jdbc;
    @Autowired GatewayFinancialResolutionService resolutions;
    @Autowired GatewaySettlementDiscoveryService discovery;
    @Autowired GatewaySettlementService settlementService;

    private AuthenticatedUser actor;
    private long periodId;
    private long runId;

    @BeforeEach
    void resolutionSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_RESOLVE','LEDGER_CORRECT')
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
        runId = insertCompletedRun(periodId);
    }

    @Test
    void statementResolutionDerivesAmountFromBoundStatementCharge() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Provider statement line reviewed"), "gwres-1");

        var adjustment = jdbc.queryForMap(
                "SELECT * FROM reconciliation_adjustment WHERE id=?", result.adjustmentId());
        assertThat(adjustment.get("adjustment_scope")).isEqualTo("GATEWAY_REQUEST");
        assertThat(adjustment.get("gateway_request_id")).isEqualTo(fixture.requestId());
        assertThat(((Number) adjustment.get("statement_charge_fact_id")).longValue())
                .isEqualTo(chargeId);
        assertThat((java.math.BigDecimal) adjustment.get("amount"))
                .isEqualByComparingTo("2.00000000");

        var entry = jdbc.queryForObject("""
                SELECT le.source_reconciliation_adjustment_id FROM ledger_entry le
                JOIN ledger_posting lp ON lp.id=le.posting_id
                WHERE lp.source_type='RECONCILIATION_ADJUSTMENT'
                """, Long.class);
        assertThat(entry).isEqualTo(result.adjustmentId());
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                java.math.BigDecimal.class, projectId)).isEqualByComparingTo("2.00000000");

        var resolution = jdbc.queryForMap(
                "SELECT * FROM gateway_financial_resolution WHERE id=?", result.resolutionId());
        assertThat(resolution.get("resolution_type")).isEqualTo("STATEMENT_ADJUSTMENT_POSTED");
        assertThat(((Number) resolution.get("statement_charge_fact_id")).longValue())
                .isEqualTo(chargeId);
        assertThat(resolution.get("reconciliation_case_id")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND match_kind='RESOLUTION_ACTION'",
                Long.class, orgId)).isEqualTo(1L);
        // The reviewer-selected binding is persisted as MANUAL_BINDING evidence.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND match_kind='MANUAL_BINDING' AND charge_fact_id=?",
                Long.class, orgId, chargeId)).isEqualTo(1L);
    }

    @Test
    void statementResolutionSubtractsInternalLedgerTruthOfTheSameRequest() {
        var fixture = insertGatewayFixture("COMPLETED", "FINAL", true, false);
        jdbc.update("UPDATE gateway_settlement SET status='RECONCILIATION_REQUIRED' WHERE id=?",
                fixture.settlementId());
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);
        // Immutable internal truth already attributable to this request: a
        // settlement posting with a direct settlement-source entry of 0.50.
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'GATEWAY_SETTLEMENT',?,NULL,?,'POSTED','SYSTEM',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_SETTLEMENT:" + fixture.settlementId(),
                fixture.settlementId(), periodId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,
                  currency,project_id,source_gateway_settlement_id,created_at)
                VALUES (?,?,0,'COST','0.50000000','USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, projectId, fixture.settlementId());

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement difference"), "gwres-int-1");

        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT amount FROM reconciliation_adjustment WHERE id=?",
                java.math.BigDecimal.class, result.adjustmentId()))
                .isEqualByComparingTo("1.50000000");
    }

    @Test
    void noChargeResolutionWorksWithoutFabricatedCaseAndZeroLedgerMutation() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        insertUnresolvedEvidence(fixture);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-case-4711", null,
                        NO_CHARGE_PROOF, "Provider portal shows no charge for this request"),
                "gwres-nc-1");

        assertThat(result.adjustmentId()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isZero();
        var resolution = jdbc.queryForMap(
                "SELECT reconciliation_case_id,reservation_outcome FROM gateway_financial_resolution WHERE id=?",
                result.resolutionId());
        assertThat(resolution.get("reconciliation_case_id")).isNull();
        assertThat(resolution.get("reservation_outcome")).isEqualTo("NONE");
        // The positive proof reference is persisted as auditable evidence.
        assertThat(jdbc.queryForObject(
                "SELECT evidence_reference FROM reconciliation_evidence "
                        + "WHERE gateway_financial_resolution_id=?",
                String.class, result.resolutionId()))
                .isEqualTo("provider-portal-case-4711");
    }

    @Test
    void noChargeWithoutRunUnresolvedEvidenceIsRejected() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        // No GATEWAY_UNRESOLVED evidence row for the request in this run.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-case-4712", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "gwres-nc-missing"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("GATEWAY_UNRESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void statementAbsenceOrGenericReasonIsNeverPositiveNoChargeProof() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        insertUnresolvedEvidence(fixture);

        // Statement absence is not a positive proof code.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "not-in-statement-aug-2026", null,
                        "NOT_FOUND_IN_STATEMENT", "No charge line was found"), "gwres-abs-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("reasonCode");
        // Generic reason without the bounded proof code is rejected as well.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "reviewer-believes-free", null,
                        "GENERIC_REVIEW", "Reviewer is sure there is no charge"),
                "gwres-abs-2"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("reasonCode");
        // Bounded proof code without a persisted evidence reference is rejected.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, null, null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "gwres-abs-3"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("positiveEvidenceReference");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void statementResolutionRequiresABoundStatementCharge() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", null, null, null,
                        "REVIEWED_STATEMENT_LINE", "No charge bound"), "gwres-reqcharge-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("statement charge");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void manualStatementBindingValidatesChargeScopeAgainstTheRequest() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        insertUnresolvedEvidence(fixture);

        // A charge of another provider account is never bindable, even in the
        // same organization and currency.
        var otherAccountId = insertProviderAccount("MIMO-scoped-" + uniqueSuffix());
        var foreignCharge = insertConfirmedStatementCharge(otherAccountId, "2.00000000");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", foreignCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "Wrong account"), "gwres-bind-acc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("provider account");

        // Wrong currency is rejected.
        var cnyCharge = insertConfirmedChargeWithCurrency(fixture.providerAccountId(), "CNY",
                "2.00000000");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", cnyCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "Wrong currency"), "gwres-bind-cur"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("currency");

        // A charge outside the run BillingPeriod window is rejected.
        var lateCharge = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000",
                "2026-09-15 00:00:00");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", lateCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "Outside period"), "gwres-bind-per"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("BillingPeriod");

        // An unconfirmed import batch is not authoritative external truth.
        var unconfirmedCharge = insertUnconfirmedStatementCharge(fixture.providerAccountId(),
                "2.00000000");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", unconfirmedCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "Unconfirmed batch"), "gwres-bind-batch"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("confirmed");

        // Excluded duplicate charges are not eligible external truth.
        var excludedCharge = insertConfirmedStatementCharge(fixture.providerAccountId(),
                "2.00000000", "2026-08-03 00:00:00", "EXCLUDED_DUPLICATE");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", excludedCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "Excluded duplicate"), "gwres-bind-review"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("review status");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void statementChargeIsBoundExclusivelyToOneRequest() {
        var first = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var second = insertSiblingRequestInSameAccount(first);
        var chargeId = insertConfirmedStatementCharge(first.providerAccountId(), "3.00000000");
        insertUnresolvedEvidence(first);
        insertUnresolvedEvidence(second);

        resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, first.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "First binding"), "gwres-excl-1");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, second.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Second binding"), "gwres-excl-2"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1L);
    }

    @Test
    void resolutionNeverCrossesRunPeriodOrCaseLineage() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);

        // A COMPLETED run of another period can never resolve this request.
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, SEP_START, "2026-10-01 00:00:00.000000", "OPEN");
        var otherPeriodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        var otherRunId = insertCompletedRun(otherPeriodId);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(otherRunId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Wrong run"), "gwres-lineage-run"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("billing period");

        // A case of another run is rejected.
        var foreignRunCaseId = insertCase(otherRunId, fixture.providerAccountId());
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, foreignRunCaseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Foreign case"), "gwres-lineage-case"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("different run");

        // A case with a mismatching provider account is rejected.
        var otherAccountId = insertProviderAccount("MIMO-scoped-" + uniqueSuffix());
        var wrongAccountCaseId = insertCase(runId, otherAccountId);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, wrongAccountCaseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Wrong account case"), "gwres-lineage-acc"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("provider account");

        // A case with a mismatching currency is rejected.
        var wrongCurrencyCaseId = insertCaseWithCurrency(runId, fixture.providerAccountId(),
                "CNY");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, wrongCurrencyCaseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Wrong currency case"), "gwres-lineage-cur"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("currency");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void eligibilityMatrixRejectsNormalM13SettlementPaths() {
        // Ordinary FINAL usage without Settlement.
        var finalFixture = insertGatewayFixture("COMPLETED", "FINAL", false, false);
        var chargeId = insertConfirmedStatementCharge(finalFixture.providerAccountId(),
                "2.00000000");
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, finalFixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "N"), "gw-el-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("FINAL");

        // PENDING Settlement.
        var pendingFixture = insertGatewayFixture("COMPLETED", "FINAL", true, false);
        var pendingCharge = insertConfirmedStatementCharge(pendingFixture.providerAccountId(),
                "2.00000000");
        jdbc.update("UPDATE gateway_settlement SET status='PENDING' WHERE id=?",
                pendingFixture.settlementId());
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, pendingFixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", pendingCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "N"), "gw-el-2"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("PENDING");

        // RETRYABLE_FAILED Settlement.
        jdbc.update("""
                UPDATE gateway_settlement SET status='RETRYABLE_FAILED',attempt_count=1
                WHERE id=?
                """, pendingFixture.settlementId());
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, pendingFixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", pendingCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "N"), "gw-el-3"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("RETRYABLE_FAILED");

        // SETTLED Settlement.
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'GATEWAY_SETTLEMENT',?,NULL,?,'POSTED','SYSTEM',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_SETTLEMENT:" + pendingFixture.settlementId(),
                pendingFixture.settlementId(), periodId);
        var settledPostingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                UPDATE gateway_settlement SET status='SETTLED',calculated_amount_raw=1.8,
                  posted_amount=1.8,rounding_delta=0,ledger_posting_id=?,settled_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, settledPostingId, pendingFixture.settlementId());
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, pendingFixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", pendingCharge, null, null,
                        "REVIEWED_STATEMENT_LINE", "N"), "gw-el-4"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SETTLED");

        // SAFE attempt never a candidate.
        var safeFixture = insertGatewayFixture("SAFE_NO_BILLABLE_EXECUTION", null, false, false);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, safeFixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-case-9", null,
                        NO_CHARGE_PROOF, "N"), "gw-el-5"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("possible-billable");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void reconciliationRequiredSettlementAcceptsStatementResolution() {
        var fixture = insertGatewayFixture("COMPLETED", "FINAL", true, false);
        jdbc.update("UPDATE gateway_settlement SET status='RECONCILIATION_REQUIRED' WHERE id=?",
                fixture.settlementId());
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.50000000");
        insertUnresolvedEvidence(fixture);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement difference"), "gwres-req-1");

        assertThat(result.adjustmentId()).isNotNull();
        // The historical settlement is not rewritten.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_settlement WHERE id=?", String.class,
                fixture.settlementId())).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void committedResolutionExcludesRequestFromDiscoveryAndSettlement() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);
        resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "gwres-late-1");

        // Late FINAL usage publication remains immutable operational evidence
        // but can never create a normal M13 Settlement.
        jdbc.update("""
                UPDATE gateway_usage_fact SET status='FINAL'
                WHERE org_id=? AND request_id=?
                """, orgId, fixture.requestId());
        jdbc.update("""
                INSERT INTO gateway_usage_dimension(org_id,usage_fact_id,dimension_code,quantity,
                  provenance)
                VALUES (?,?,'INPUT_TOKEN',1,'PROVIDER_FINAL')
                """, orgId, fixture.usageFactId());
        assertThat(discovery.discover(orgId)).isEmpty();

        // Even a directly created PENDING Settlement cannot be settled.
        jdbc.update("""
                INSERT INTO gateway_settlement(
                  org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                  billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                  provider_model_id,pricing_version_id,currency,status,attempt_count,
                  created_at,updated_at)
                VALUES (?,?,?,?,?,NULL,?,'PROJECT',?,?,?,?,?,'PENDING',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_REQUEST:" + UUID.randomUUID(), fixture.requestId(),
                fixture.attemptId(), fixture.usageFactId(), periodId, projectId,
                fixture.providerAccountId(), fixture.providerModelId(), fixture.pricingVersionId(),
                "USD");
        var settlementId = jdbc.queryForObject(
                "SELECT id FROM gateway_settlement WHERE org_id=? AND request_id=?",
                Long.class, orgId, fixture.requestId());
        var settled = settlementService.settle(orgId, settlementId);
        assertThat(settled.settlement().status().name()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(settled.settlement().lastErrorCode())
                .isEqualTo("GATEWAY_FINANCIAL_RESOLUTION_EXISTS");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND source_type='GATEWAY_SETTLEMENT'",
                Long.class, orgId)).isZero();
    }

    @Test
    void requestResolutionNeverResolvesSiblingCaseEvidence() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, fixture.providerAccountId());
        var caseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // Run finalization attaches the request's unresolved evidence to the
        // matching aggregate case; the client caseId is an equality assertion
        // against that reviewed lineage.
        insertUnresolvedEvidenceForCase(fixture, caseId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, caseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "gwres-sib-1");

        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reconciliation_case WHERE id=?", String.class, caseId))
                .isEqualTo("OPEN");
    }

    @Test
    void noChargeResolutionReleasesEffectiveReservation() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, true);
        insertUnresolvedEvidence(fixture);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-case-8", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "gwres-rel-1");

        assertThat(result.reservationOutcome()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("RELEASED");
    }

    @Test
    void statementResolutionFinalizesEffectiveReservation() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, true);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "gwres-fin-1");

        assertThat(result.reservationOutcome()).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("FINALIZED");
    }

    @Test
    void idempotentReplayReturnsTheCommittedBusinessResponse() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,
                  updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        var command = new GatewayResolutionCommand(runId, null, fixture.requestId(),
                "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                "REVIEWED_STATEMENT_LINE", "Reviewed statement line");

        var first = resolutions.resolveGatewayFinancialWork(actor, command, "gw-replay-key");
        var replay = resolutions.resolveGatewayFinancialWork(actor, command, "gw-replay-key");

        // Same key + same canonical request replays the committed business
        // response field by field, not a degraded echo.
        assertThat(replay.resolutionId()).isEqualTo(first.resolutionId());
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(replay.caseId()).isEqualTo(first.caseId());
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(replay.resolutionType()).isEqualTo(first.resolutionType());
        assertThat(replay.reservationOutcome()).isEqualTo(first.reservationOutcome());
        assertThat(replay.adjustmentId()).isEqualTo(first.adjustmentId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                java.math.BigDecimal.class, projectId)).isEqualByComparingTo("2.00000000");

        // Same key + different canonical request is a deterministic conflict.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "A different reviewed summary"),
                "gw-replay-key"))
                .isInstanceOf(DomainException.class);
    }

    // ------------------------------------------------------------------
    // Sol Round 4: adjustment period rules, conditional commitment, server-
    // derived case lineage
    // ------------------------------------------------------------------

    @Test
    void openRequestCannotPostGatewayAdjustmentIntoAnotherOpenPeriod() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        insertUnresolvedEvidence(fixture);
        var septemberPeriodId = insertOpenBillingPeriod();

        // The original period is still OPEN: the adjustment can never be
        // diverted into another OPEN period.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, septemberPeriodId,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-per-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("OPEN");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget WHERE org_id=?", Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void closingOriginalPeriodRejectsGatewayResolution() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        insertUnresolvedEvidence(fixture);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", periodId);

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-per-closing"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLOSING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void closedRequestCanPostIntoDifferentOpenCorrectionPeriod() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var fin = insertCommitmentBackedReservation(fixture, periodId);
        insertUnresolvedEvidence(fixture);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);
        var septemberPeriodId = insertOpenBillingPeriod();
        var septemberBudgetId = insertBudgetForPeriod(septemberPeriodId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, septemberPeriodId,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-per-2");

        // The adjustment posts into the explicit OPEN correction period.
        assertThat(jdbc.queryForObject(
                "SELECT adjustment_period_id FROM reconciliation_adjustment WHERE id=?",
                Long.class, result.adjustmentId())).isEqualTo(septemberPeriodId);
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                septemberBudgetId)).isEqualByComparingTo("2.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                fin.budgetId())).isEqualByComparingTo("0.00000000");
        // The historical commitment is never consumed by a cross-period
        // adjustment, and the reservation still reaches its terminal state.
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?",
                java.math.BigDecimal.class, fin.commitmentId()))
                .isEqualByComparingTo("5.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fin.reservationId())).isEqualTo("FINALIZED");
    }

    @Test
    void reopenedHistoricalPeriodCanPostBackIntoSamePeriod() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var fin = insertCommitmentBackedReservation(fixture, periodId);
        insertUnresolvedEvidence(fixture);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        // Simulate an explicit governed PERIOD_REOPEN: the historical period is
        // OPEN again, so a same-period adjustment is legal.
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);
        jdbc.update("UPDATE billing_period SET status='OPEN' WHERE id=?", periodId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-per-3");

        assertThat(jdbc.queryForObject(
                "SELECT adjustment_period_id FROM reconciliation_adjustment WHERE id=?",
                Long.class, result.adjustmentId())).isEqualTo(periodId);
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                fin.budgetId())).isEqualByComparingTo("2.00000000");
    }

    @Test
    void crossPeriodGatewayAdjustmentWithBoundCommitmentSucceedsWithoutConsumption() {
        // Covered in depth by closedRequestCanPostIntoDifferentOpenCorrectionPeriod;
        // this variant proves the same invariant for a RECONCILIATION_REQUIRED
        // settlement internal truth so both cross-period paths keep the
        // commitment untouched.
        var fixture = insertGatewayFixture("COMPLETED", "FINAL", true, false);
        jdbc.update("UPDATE gateway_settlement SET status='RECONCILIATION_REQUIRED' WHERE id=?",
                fixture.settlementId());
        var fin = insertCommitmentBackedReservation(fixture, periodId);
        insertUnresolvedEvidence(fixture);
        seedSettlementPosting(fixture.settlementId(), "1.00000000");
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "3.00000000");
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);
        var septemberPeriodId = insertOpenBillingPeriod();
        var septemberBudgetId = insertBudgetForPeriod(septemberPeriodId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, septemberPeriodId,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-cmt-cross");

        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT amount FROM reconciliation_adjustment WHERE id=?",
                java.math.BigDecimal.class, result.adjustmentId()))
                .isEqualByComparingTo("2.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                septemberBudgetId)).isEqualByComparingTo("2.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?",
                java.math.BigDecimal.class, fin.commitmentId()))
                .isEqualByComparingTo("5.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void negativeGatewayAdjustmentWithBoundCommitmentSucceedsWithoutConsumption() {
        var fixture = insertGatewayFixture("COMPLETED", "FINAL", true, false);
        jdbc.update("UPDATE gateway_settlement SET status='RECONCILIATION_REQUIRED' WHERE id=?",
                fixture.settlementId());
        var fin = insertCommitmentBackedReservation(fixture, periodId);
        insertUnresolvedEvidence(fixture);
        seedSettlementPosting(fixture.settlementId(), "3.00000000");
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-cmt-neg");

        // external 2 - internal 3 = -1: the adjustment stays legal and the
        // commitment is simply not consumed.
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT amount FROM reconciliation_adjustment WHERE id=?",
                java.math.BigDecimal.class, result.adjustmentId()))
                .isEqualByComparingTo("-1.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                fin.budgetId())).isEqualByComparingTo("-1.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?",
                java.math.BigDecimal.class, fin.commitmentId()))
                .isEqualByComparingTo("5.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fin.reservationId())).isEqualTo("FINALIZED");
    }

    @Test
    void positiveSamePeriodGatewayAdjustmentConsumesBoundCommitmentExactlyOnce() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var fin = insertCommitmentBackedReservation(fixture, periodId);
        insertUnresolvedEvidence(fixture);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-cmt-pos");

        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT amount FROM reconciliation_adjustment WHERE id=?",
                java.math.BigDecimal.class, result.adjustmentId()))
                .isEqualByComparingTo("2.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE id=?", java.math.BigDecimal.class,
                fin.budgetId())).isEqualByComparingTo("2.00000000");
        assertThat((java.math.BigDecimal) jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?",
                java.math.BigDecimal.class, fin.commitmentId()))
                .isEqualByComparingTo("3.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=? "
                        + "AND budget_commitment_id=?",
                Long.class, orgId, fin.commitmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(consumed_amount),0) FROM budget_commitment_usage "
                        + "WHERE org_id=? AND budget_commitment_id=?",
                java.math.BigDecimal.class, orgId, fin.commitmentId()))
                .isEqualByComparingTo("2.00000000");
    }

    @Test
    void noChargeConfirmationRejectsMeaninglessCorrectionPeriod() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        insertUnresolvedEvidence(fixture);
        var septemberPeriodId = insertOpenBillingPeriod();

        // NO_CHARGE_CONFIRMED posts no adjustment, so a correction period is
        // meaningless and must be rejected instead of silently ignored.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r4-1",
                        septemberPeriodId, NO_CHARGE_PROOF,
                        "Provider confirmed no charge"), "r4-nc-period"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("correction period");
    }

    @Test
    void resolutionAutomaticallyUsesCaseFromReviewedRunEvidence() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        var caseId = insertCase(runId, fixture.providerAccountId());
        insertUnresolvedEvidenceForCase(fixture, caseId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-case-1");

        // The client omitted the case: the server adopts the reviewed evidence
        // case lineage and every downstream row carries it.
        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(jdbc.queryForObject(
                "SELECT reconciliation_case_id FROM gateway_financial_resolution WHERE id=?",
                Long.class, result.resolutionId())).isEqualTo(caseId);
        assertThat(jdbc.queryForObject(
                "SELECT reconciliation_case_id FROM reconciliation_adjustment WHERE id=?",
                Long.class, result.adjustmentId())).isEqualTo(caseId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND match_kind='RESOLUTION_ACTION' AND reconciliation_case_id=?",
                Long.class, orgId, caseId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND match_kind='MANUAL_BINDING' AND reconciliation_case_id=?",
                Long.class, orgId, caseId)).isEqualTo(1L);
    }

    @Test
    void resolutionRejectsClientCaseDifferentFromReviewedEvidence() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        var evidenceCaseId = insertCase(runId, fixture.providerAccountId());
        insertUnresolvedEvidenceForCase(fixture, evidenceCaseId);
        // The scope unique key (run, provider account, currency) forbids a
        // second same-scope case inside the reviewed run, so a client case
        // different from the reviewed evidence case can only be a case of
        // another run — which must be rejected whatever guard fires first.
        var otherRunId = insertCompletedRun(periodId);
        var clientCaseId = insertCase(otherRunId, fixture.providerAccountId());

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, clientCaseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-case-2"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("case");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void caseNullEvidenceRejectsInventedClientCase() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        var inventedCaseId = insertCase(runId, fixture.providerAccountId());
        insertUnresolvedEvidence(fixture);

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, inventedCaseId, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r4-2", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "r4-case-3"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("case");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void inconsistentExactEvidenceCaseFailsClosed() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        var unresolvedCaseId = insertCase(runId, fixture.providerAccountId());
        insertUnresolvedEvidenceForCase(fixture, unresolvedCaseId);
        // Run finalization attaches exact and unresolved evidence of one scope
        // to the same aggregate case; a case-less exact row next to a
        // case-bound unresolved row is inconsistent generation state.
        insertExactEvidenceForCase(chargeId, fixture, null);

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, unresolvedCaseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_EXACT_LINE", "Exact correlation reviewed"), "r4-case-4"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("inconsistent");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void resolutionAcceptsClientCaseEqualToReviewedEvidenceCase() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(), "2.00000000");
        var caseId = insertCase(runId, fixture.providerAccountId());
        insertUnresolvedEvidenceForCase(fixture, caseId);

        // A client caseId equal to the reviewed evidence case is accepted as a
        // pure equality assertion.
        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, caseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"), "r4-case-5");

        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(jdbc.queryForObject(
                "SELECT reconciliation_case_id FROM gateway_financial_resolution WHERE id=?",
                Long.class, result.resolutionId())).isEqualTo(caseId);
    }

    @Test
    void closedPeriodNoChargeConfirmationSucceedsWithoutCorrectionPeriod() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, true);
        insertUnresolvedEvidence(fixture);

        // NO_CHARGE_CONFIRMED posts no Ledger adjustment and selects no
        // correction period: a CLOSED historical run may still record the
        // reviewed terminal decision and release the effective reservation.
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r5-closed", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "r5-nc-closed");

        assertThat(result.reservationOutcome()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT resolution_type FROM gateway_financial_resolution WHERE id=?",
                String.class, result.resolutionId())).isEqualTo("NO_CHARGE_CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type='RECONCILIATION_ADJUSTMENT'",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("RELEASED");
    }

    @Test
    void closingPeriodNoChargeConfirmationRejects() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        insertUnresolvedEvidence(fixture);
        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", periodId);

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r5-closing", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "r5-nc-closing"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("CLOSING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void noChargeRejectsCurrentValidNonZeroExactStatementCharge() {
        // The same run/request carries both a current valid non-zero
        // EXACT_PROVIDER_REQUEST (authoritative statement Charge > 0, same
        // account/currency/attempt) and GATEWAY_UNRESOLVED evidence: a
        // NO_CHARGE_CONFIRMED must fail closed instead of silently succeeding.
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, true);
        insertUnresolvedEvidence(fixture);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(),
                "10.00000000");
        insertExactEvidenceForCase(chargeId, fixture, null);

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r6-fence", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "r6-nc-exact-fence"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("exact");
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
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND match_kind='RESOLUTION_ACTION'",
                Long.class, orgId)).isZero();
        // The idempotency reservation rolls back with the rejected
        // transaction: retrying the same key fails again instead of replaying.
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r6-fence", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "r6-nc-exact-fence"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void zeroExactProviderRecordAllowsNoChargeOnlyWithExplicitZeroProof() {
        // An exact zero provider record may resolve NO_CHARGE only with the
        // explicit zero proof; a portal/support proof against the same zero
        // exact record still fails closed.
        var allowed = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        insertUnresolvedEvidence(allowed);
        var allowedChargeId = insertConfirmedStatementCharge(allowed.providerAccountId(),
                "0.00000000");
        insertExactEvidenceForCase(allowedChargeId, allowed, null);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, allowed.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-zero-record-r6", null,
                        "EXPLICIT_ZERO_PROVIDER_RECORD", "Provider record is explicitly zero"),
                "r6-nc-zero-explicit");
        assertThat(jdbc.queryForObject(
                "SELECT resolution_type FROM gateway_financial_resolution WHERE id=?",
                String.class, result.resolutionId())).isEqualTo("NO_CHARGE_CONFIRMED");

        var rejected = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);
        insertUnresolvedEvidence(rejected);
        var rejectedChargeId = insertConfirmedStatementCharge(rejected.providerAccountId(),
                "0.00000000");
        insertExactEvidenceForCase(rejectedChargeId, rejected, null);

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, rejected.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-r6-zero", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"), "r6-nc-zero-portal"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unknownRequestIsNotFound() {
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, 999999L,
                        "NO_CHARGE_CONFIRMED", null, "provider-portal-case-1", null,
                        NO_CHARGE_PROOF, "N"), "gw-unknown"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Gateway request");
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private record Fixture(long requestId, long attemptId, Long usageFactId, Long settlementId,
            Long reservationId, long providerAccountId, long providerModelId,
            long pricingVersionId) {
    }

    /** usageStatus null = no usage fact; withSettlement requires FINAL usage. */
    private Fixture insertGatewayFixture(String attemptStatus, String usageStatus,
            boolean withSettlement, boolean withReservation) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "gwr-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "gwr-model-" + suffix, suffix);
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
                """, providerCode, modelId, "gwr-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, suffix, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(71), serviceId, projectId, projectId);
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
                modelId, digest(72), digest(73), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), providerAccountId, providerModelId,
                pricingVersionId, attemptStatus);
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

        Long settlementId = null;
        if (withSettlement) {
            jdbc.update("""
                    INSERT INTO gateway_settlement(
                      org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                      billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                      provider_model_id,pricing_version_id,currency,status,attempt_count,
                      created_at,updated_at)
                    VALUES (?,?,?,?,?,NULL,?,'PROJECT',?,?,?,?,?,'PENDING',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, "GATEWAY_REQUEST:" + suffix, requestId, attemptId, usageFactId,
                    periodId, projectId, providerAccountId, providerModelId, pricingVersionId,
                "USD");
            settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }

        Long reservationId = null;
        if (withReservation) {
            jdbc.update("""
                    INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                      total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                    VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, periodId, projectId);
            var budgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("""
                    INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,
                      billing_period_id,budget_id,financial_scope_type,financial_scope_id,currency,
                      reserved_amount,commitment_id,commitment_backed_amount,status,version,
                      expires_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,'PROJECT',?,'USD','5.00000000',NULL,0,'ACTIVE',0,
                      UTC_TIMESTAMP(6) + INTERVAL 7 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, periodId, budgetId, projectId);
            reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        return new Fixture(requestId, attemptId, usageFactId, settlementId, reservationId,
                providerAccountId, providerModelId, pricingVersionId);
    }

    /**
     * A second possible-billable request inside the same provider account,
     * provider model and pricing version as the base fixture: needed to prove
     * statement charge exclusivity between requests of one scope.
     */
    private Fixture insertSiblingRequestInSameAccount(Fixture base) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
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
                """, fixedRequestId(), digest(74), digest(75), base.requestId());
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
        return new Fixture(requestId, attemptId, usageFactId, null, null,
                base.providerAccountId(), base.providerModelId(), base.pricingVersionId());
    }

    private long insertCompletedRun(long billingPeriodId) {
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, billingPeriodId, "e".repeat(64), actorMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? AND billing_period_id=? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, orgId, billingPeriodId);
    }

    private void insertUnresolvedEvidence(Fixture fixture) {
        insertUnresolvedEvidenceForCase(fixture, null);
    }

    private void insertUnresolvedEvidenceForCase(Fixture fixture, Long caseId) {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(org_id,reconciliation_run_id,
                  reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,gateway_request_id,
                  gateway_route_attempt_id,gateway_usage_fact_id,gateway_settlement_id,created_at)
                VALUES (?,?,?,CONCAT('GATEWAY_UNRESOLVED:REQUEST:',?),?,?,'GATEWAY_UNRESOLVED',
                  ?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, fixture.requestId(), fixture.providerAccountId(), "USD",
                fixture.requestId(), fixture.attemptId(), fixture.usageFactId(),
                fixture.settlementId());
    }

    private void insertExactEvidenceForCase(long chargeId, Fixture fixture, Long caseId) {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(org_id,reconciliation_run_id,
                  reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,charge_fact_id,gateway_request_id,
                  gateway_route_attempt_id,provider_request_id,created_at)
                VALUES (?,?,?,?,?,'USD','EXACT_PROVIDER_REQUEST',?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, "EXACT:CHARGE:" + chargeId + ":REQUEST:"
                        + fixture.requestId(), fixture.providerAccountId(), chargeId,
                fixture.requestId(), fixture.attemptId(), "r4-req-" + fixture.requestId());
    }

    private long insertOpenBillingPeriod() {
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, SEP_START, "2026-10-01 00:00:00.000000", "OPEN");
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
    }

    private long insertBudgetForPeriod(long budgetPeriodId) {
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,
                  updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,'5.00000000','ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, budgetPeriodId, projectId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private record CommitmentFixture(long budgetId, long commitmentId, long reservationId) {
    }

    private CommitmentFixture insertCommitmentBackedReservation(Fixture fixture,
            long budgetPeriodId) {
        var budgetId = insertBudgetForPeriod(budgetPeriodId);
        jdbc.update("""
                INSERT INTO budget_commitment(org_id,budget_id,status,requested_amount,
                  approved_amount,remaining_amount,version,created_at,updated_at)
                VALUES (?,?,'ACTIVE','5.00000000','5.00000000','5.00000000',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, budgetId);
        var commitmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,
                  billing_period_id,budget_id,financial_scope_type,financial_scope_id,currency,
                  reserved_amount,commitment_id,commitment_backed_amount,status,version,
                  expires_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'PROJECT',?,'USD','5.00000000',?,?,'ACTIVE',0,
                  UTC_TIMESTAMP(6) + INTERVAL 7 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixture.requestId(), fixture.attemptId(), budgetPeriodId, budgetId,
                projectId, commitmentId, new java.math.BigDecimal("2.00000000"));
        var reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new CommitmentFixture(budgetId, commitmentId, reservationId);
    }

    private void seedSettlementPosting(Long settlementId, String amount) {
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'GATEWAY_SETTLEMENT',?,NULL,?,'POSTED','SYSTEM',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_SETTLEMENT:" + settlementId, settlementId, periodId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,
                  currency,project_id,source_gateway_settlement_id,created_at)
                VALUES (?,?,0,'COST',?,'USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, amount, projectId, settlementId);
    }

    private long insertCase(long runId, long providerAccountId) {
        return insertCaseWithCurrency(runId, providerAccountId, "USD");
    }

    private long insertCaseWithCurrency(long runId, long providerAccountId, String currency) {
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,?,'AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, providerAccountId, currency);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** Confirmed USD GLM statement charge for the given provider account. */
    private long insertConfirmedStatementCharge(long providerAccountId, String amount) {
        return insertConfirmedStatementCharge(providerAccountId, amount,
                "2026-08-05 00:00:00", "CLEAN");
    }

    private long insertConfirmedStatementCharge(long providerAccountId, String amount,
            String periodStart) {
        return insertConfirmedStatementCharge(providerAccountId, amount, periodStart, "CLEAN");
    }

    private long insertConfirmedStatementCharge(long providerAccountId, String amount,
            String periodStart, String reviewStatus) {
        var rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, providerAccountId,
                uniqueSuffix());
        return insertChargeRecord(rawRecordId, amount, "USD", periodStart, reviewStatus);
    }

    private long insertUnconfirmedStatementCharge(long providerAccountId, String amount) {
        var rawRecordId = insertUnconfirmedRawRecord(orgId, actorMemberId, providerAccountId,
                uniqueSuffix());
        return insertChargeRecord(rawRecordId, amount, "USD", "2026-08-05 00:00:00", "CLEAN");
    }

    private long insertConfirmedChargeWithCurrency(long providerAccountId, String currency,
            String amount) {
        var rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, providerAccountId,
                uniqueSuffix());
        return insertChargeRecord(rawRecordId, amount, currency, "2026-08-05 00:00:00", "CLEAN");
    }

    private long insertChargeRecord(long rawRecordId, String amount, String currency,
            String periodStart, String reviewStatus) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,0,'GLM','USAGE',?,?,?,DATE_ADD(?, INTERVAL 1 DAY),?,
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, currency, periodStart, periodStart,
                reviewStatus);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgId, rawRecordId);
    }

    private long insertProviderAccount(String providerCode) {
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?, 'MIMO', 'https://provider.invalid', 'ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, providerCode);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, providerCode, providerCode);
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, orgId, providerCode);
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
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
