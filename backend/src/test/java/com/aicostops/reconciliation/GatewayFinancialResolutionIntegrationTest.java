package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.application.GatewaySettlementService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
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
 */
@SpringBootTest
@Tag("integration")
class GatewayFinancialResolutionIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired GatewayFinancialResolutionService resolutions;
    @Autowired GatewaySettlementDiscoveryService discovery;
    @Autowired GatewaySettlementService settlementService;
    @Autowired ReconciliationMapper reconciliationMapper;

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
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, periodId, "e".repeat(64), actorMemberId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void statementResolutionForUnknownUsagePostsRequestAdjustment() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("2.00000000"), null, null,
                        "STATEMENT_EVIDENCE", "Provider statement line reviewed"), "gwres-1");

        var adjustment = jdbc.queryForMap(
                "SELECT * FROM reconciliation_adjustment WHERE id=?", result.adjustmentId());
        assertThat(adjustment.get("adjustment_scope")).isEqualTo("GATEWAY_REQUEST");
        assertThat(adjustment.get("gateway_request_id")).isEqualTo(fixture.requestId());
        assertThat(((BigDecimal) adjustment.get("amount"))).isEqualByComparingTo("2.00000000");

        var entry = jdbc.queryForObject("""
                SELECT le.source_reconciliation_adjustment_id FROM ledger_entry le
                JOIN ledger_posting lp ON lp.id=le.posting_id
                WHERE lp.source_type='RECONCILIATION_ADJUSTMENT'
                """, Long.class);
        assertThat(entry).isEqualTo(result.adjustmentId());
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                BigDecimal.class, projectId)).isEqualByComparingTo("2.00000000");

        var resolution = jdbc.queryForMap(
                "SELECT * FROM gateway_financial_resolution WHERE id=?", result.resolutionId());
        assertThat(resolution.get("resolution_type")).isEqualTo("STATEMENT_ADJUSTMENT_POSTED");
        assertThat(resolution.get("reconciliation_case_id")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? AND match_kind='RESOLUTION_ACTION'",
                Long.class, orgId)).isEqualTo(1);
    }

    @Test
    void noChargeResolutionWorksWithoutFabricatedCaseAndZeroLedgerMutation() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, false);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, null, null,
                        "POSITIVE_NO_CHARGE", "Provider confirmed no charge for this request"),
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
    }

    @Test
    void eligibilityMatrixRejectsNormalM13SettlementPaths() {
        // Ordinary FINAL usage without Settlement.
        var finalFixture = insertGatewayFixture("COMPLETED", "FINAL", false, false);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, finalFixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, null, null, "R", "N"), "gw-el-1"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("FINAL");

        // PENDING Settlement.
        var pendingFixture = insertGatewayFixture("COMPLETED", "FINAL", true, false);
        jdbc.update("UPDATE gateway_settlement SET status='PENDING' WHERE id=?",
                pendingFixture.settlementId());
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, pendingFixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("1.00000000"), null, null,
                        "R", "N"), "gw-el-2"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("PENDING");

        // RETRYABLE_FAILED Settlement.
        jdbc.update("""
                UPDATE gateway_settlement SET status='RETRYABLE_FAILED',attempt_count=1
                WHERE id=?
                """, pendingFixture.settlementId());
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, pendingFixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("1.00000000"), null, null,
                        "R", "N"), "gw-el-3"))
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
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("1.00000000"), null, null,
                        "R", "N"), "gw-el-4"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("SETTLED");

        // SAFE attempt never a candidate.
        var safeFixture = insertGatewayFixture("SAFE_NO_BILLABLE_EXECUTION", null, false, false);
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, safeFixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, null, null, "R", "N"), "gw-el-5"))
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

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("2.50000000"), null, null,
                        "STATEMENT_EVIDENCE", "Reviewed statement difference"),
                "gwres-req-1");

        assertThat(result.adjustmentId()).isNotNull();
        // The historical settlement is not rewritten.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_settlement WHERE id=?", String.class,
                fixture.settlementId())).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void committedResolutionExcludesRequestFromDiscoveryAndSettlement() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("2.00000000"), null, null,
                        "STATEMENT_EVIDENCE", "Reviewed statement line"), "gwres-late-1");

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
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, fixture.providerAccountId());
        var caseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, caseId, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("2.00000000"), null, null,
                        "STATEMENT_EVIDENCE", "Reviewed statement line"), "gwres-sib-1");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM reconciliation_case WHERE id=?", String.class, caseId))
                .isEqualTo("OPEN");
    }

    @Test
    void noChargeResolutionReleasesEffectiveReservation() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", null, false, true);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, null, null,
                        "POSITIVE_NO_CHARGE", "Provider confirmed no charge"), "gwres-rel-1");

        assertThat(result.reservationOutcome()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("RELEASED");
    }

    @Test
    void statementResolutionFinalizesEffectiveReservation() {
        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, true);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", new BigDecimal("2.00000000"), null, null,
                        "STATEMENT_EVIDENCE", "Reviewed statement line"), "gwres-fin-1");

        assertThat(result.reservationOutcome()).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("FINALIZED");
    }

    @Test
    void unknownRequestIsNotFound() {
        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, 999999L,
                        "NO_CHARGE_CONFIRMED", null, null, null, "R", "N"), "gw-unknown"))
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
