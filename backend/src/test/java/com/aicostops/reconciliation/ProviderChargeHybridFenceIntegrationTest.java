package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M15 Hybrid Provider Charge posting fence: a statement Charge that overlaps
 * durable possibly-billable Gateway facts must not be posted through the
 * normal V1 path unless an explicit DIRECT_PROVIDER_CHARGE disposition exists.
 * RECONCILIATION_EVIDENCE dispositions are permanently non-postable, already
 * posted legacy charges stay replayable, and non-Hybrid posting is unchanged.
 */
@SpringBootTest
@Tag("integration")
class ProviderChargeHybridFenceIntegrationTest extends AllocationApiTestSupport {

    private static final String JAN_START = "2026-01-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderChargePostingService postings;

    private long periodId;
    private long chargeId;
    private long decisionId;
    private AuthenticatedUser actor;

    @BeforeEach
    void fenceSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code='LEDGER_POST'
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,? ,'2026-02-01 00:00:00','OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_START);
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        chargeId = insertUsdCharge("10.00000000");
        decisionId = insertUsdDecision();
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
    }

    @Test
    void hybridOverlapWithoutDispositionBlocksPosting() {
        insertBillableGatewayAttempt("COMPLETED");

        assertThatThrownBy(() -> postings.post(actor, chargeId, new PostSourceCommand(List.of())))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("HYBRID");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                BigDecimal.class, projectId))
                .isEqualByComparingTo("0.00000000");
    }

    @Test
    void directDispositionAllowsNormalPosting() {
        insertBillableGatewayAttempt("COMPLETED");
        insertDisposition("DIRECT_PROVIDER_CHARGE", "MANUAL");

        var result = postings.post(actor, chargeId, new PostSourceCommand(List.of()));

        assertThat(result.posting().sourceType().name()).isEqualTo("PROVIDER_CHARGE");
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                BigDecimal.class, projectId))
                .isEqualByComparingTo("10.00000000");
    }

    @Test
    void reconciliationEvidenceDispositionIsPermanentlyNonPostable() {
        insertBillableGatewayAttempt("COMPLETED");
        insertDisposition("RECONCILIATION_EVIDENCE", "MANUAL");

        assertThatThrownBy(() -> postings.post(actor, chargeId, new PostSourceCommand(List.of())))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("RECONCILIATION_EVIDENCE");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isZero();
    }

    @Test
    void nonHybridChargePostingIsUnchanged() {
        var result = postings.post(actor, chargeId, new PostSourceCommand(List.of()));

        assertThat(result.posting().sourceType().name()).isEqualTo("PROVIDER_CHARGE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isEqualTo(1);
    }

    @Test
    void alreadyPostedLegacyChargeRemainsReplayableUnderLaterOverlap() {
        var first = postings.post(actor, chargeId, new PostSourceCommand(List.of()));
        insertBillableGatewayAttempt("COMPLETED");

        var replay = postings.post(actor, chargeId, new PostSourceCommand(List.of()));

        assertThat(replay.posting().id()).isEqualTo(first.posting().id());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isEqualTo(1);
        // Legacy replay does not reclassify the committed posting.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long insertUsdCharge(String amount) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,999,'GLM','USAGE',?,'USD',?,'2026-01-02 00:00:00','CLEAN',
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, JAN_START);
        return jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);
    }

    private long insertUsdDecision() {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var id = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '10.00000000','USD', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, id, projectId);
        return id;
    }

    private void insertDisposition(String disposition, String source) {
        jdbc.update("""
                INSERT INTO provider_charge_disposition(
                  org_id,charge_fact_id,disposition,decision_source,decided_by_member_id,
                  reason_code,resolution_note,created_at)
                VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, chargeId, disposition, source, actorMemberId,
                "MANUAL_REVIEW", "Reviewed direct provider cost");
    }

    /** Billable Gateway attempt on the same provider account/currency/period. */
    private void insertBillableGatewayAttempt(String attemptStatus) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "fence-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "fence-model-" + suffix, suffix);
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
                """, providerCode, modelId, "fence-wire-" + suffix);
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
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(41), serviceId, projectId, projectId);
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
                modelId, digest(42), digest(43), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), accountId, providerModelId,
                pricingVersionId, attemptStatus);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
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
