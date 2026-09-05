package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V23 contract for M15 hybrid reconciliation: bounded terminal lineage tables,
 * Ledger forward extension, same-org FKs, business uniqueness and the legacy
 * Provider Charge disposition backfill.
 */
@SpringBootTest
@Tag("integration")
class M15HybridSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long memberId;
    private long userId;
    private long providerAccountId;
    private long periodId;
    private long projectId;
    private long chargeId;
    private long runId;
    private long caseId;
    private long requestId;
    private long attemptId;
    private long usageFactId;
    private long otherOrgId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "m15-schema-" + UUID.randomUUID();
        orgId = insertOrganization(suffix);
        otherOrgId = insertOrganization(suffix + "-other");

        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,
                  created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.test", suffix);
        userId = lastId();
        memberId = insertMember(orgId, userId);
        insertMember(otherOrgId, userId);

        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,status,
                  created_at,updated_at)
                VALUES (?,'OPENAI',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix);
        providerAccountId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,status,
                  created_at,updated_at)
                VALUES (?,'OPENAI','foreign-account','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, otherOrgId);
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "proj-" + suffix, suffix);
        projectId = lastId();
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,
                  version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00','2026-09-01 00:00:00','OPEN',0,0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        periodId = lastId();

        chargeId = insertCharge(suffix);

        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, periodId, "a".repeat(64), memberId);
        runId = lastId();
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','12.00000000','10.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, providerAccountId);
        caseId = lastId();

        var gateway = insertGatewayChain(suffix);
        requestId = gateway[0];
        attemptId = gateway[1];
        usageFactId = gateway[2];
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void migrationCreatesM15TablesAndLedgerExtension() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN (
                  'provider_charge_disposition','reconciliation_adjustment',
                  'gateway_financial_resolution','reconciliation_evidence')
                """, String.class)).containsExactlyInAnyOrder(
                "provider_charge_disposition", "reconciliation_adjustment",
                "gateway_financial_resolution", "reconciliation_evidence");

        assertThat(columnType("ledger_entry", "source_reconciliation_adjustment_id"))
                .isEqualTo("bigint");
        assertThat(columnType("reconciliation_adjustment", "amount"))
                .isEqualTo("decimal(20,8)");
        assertThat(columnType("reconciliation_adjustment", "currency")).isEqualTo("char(3)");

        // Ledger forward extension accepts the new bounded source type.
        insertPosting("RECONCILIATION_ADJUSTMENT_POSTING:" + UUID.randomUUID(),
                "RECONCILIATION_ADJUSTMENT", 1);
        assertThatThrownBy(() -> insertPosting("BAD:" + UUID.randomUUID(), "NOT_A_SOURCE", 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_ledger_posting_source_type");
    }

    @Test
    void chargeDispositionIsUniquePerChargeAndBounded() {
        insertDisposition(chargeId, "DIRECT_PROVIDER_CHARGE", "MANUAL", memberId, "MANUAL_DIRECT",
                "Reviewed direct charge");
        assertThatThrownBy(() -> insertDisposition(chargeId, "RECONCILIATION_EVIDENCE", "MANUAL",
                memberId, "MANUAL_EVIDENCE", "Second disposition"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_provider_charge_disposition_org_charge");

        // MySQL check evaluation order between the bounded-value constraints
        // is not guaranteed; any provider_charge_disposition CHECK rejection
        // proves the invalid shape cannot persist.
        assertThatThrownBy(() -> insertDisposition(chargeId, "NOT_A_DISPOSITION", "MANUAL",
                memberId, "R", "N"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_provider_charge_disposition");
        assertThatThrownBy(() -> insertDisposition(chargeId, "DIRECT_PROVIDER_CHARGE",
                "NOT_A_SOURCE", memberId, "R", "N"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_provider_charge_disposition");

        // MANUAL requires member + bounded reason/note; system/legacy never impersonate.
        assertThatThrownBy(() -> insertDisposition(chargeId, "DIRECT_PROVIDER_CHARGE", "MANUAL",
                null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_provider_charge_disposition_actor");
        assertThatThrownBy(() -> insertDisposition(chargeId, "DIRECT_PROVIDER_CHARGE",
                "LEGACY_POSTED", memberId, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_provider_charge_disposition_actor");
        // SYSTEM_EXACT requires the reconciliation run lineage.
        assertThatThrownBy(() -> insertDisposition(chargeId, "DIRECT_PROVIDER_CHARGE",
                "SYSTEM_EXACT", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_provider_charge_disposition_system_lineage");
    }

    @Test
    void chargeDispositionRejectsForeignOrganizationParents() {
        var otherCharge = insertChargeForOrg(otherOrgId, "foreign-charge");
        assertThatThrownBy(() -> insertDisposition(otherCharge, "DIRECT_PROVIDER_CHARGE", "MANUAL",
                memberId, "R", "N"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_provider_charge_disposition_charge_org");
        assertThatThrownBy(() -> insertDisposition(chargeId, "DIRECT_PROVIDER_CHARGE", "MANUAL",
                otherOrgMemberId(), "R", "N"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_provider_charge_disposition_member_org");
    }

    @Test
    void adjustmentStructuralConstraintsAreDatabaseEnforced() {
        insertAdjustment("CASE_FULL", caseId, null, null);
        assertThatThrownBy(() -> insertAdjustment("CASE_FULL", caseId, requestId, attemptId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_adjustment_scope_shape");
        assertThatThrownBy(() -> insertAdjustment("CASE_FULL", null, null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_adjustment_scope_shape");
        assertThatThrownBy(() -> insertAdjustment("GATEWAY_REQUEST", null, requestId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_adjustment_scope_shape");
        assertThatThrownBy(() -> insertAdjustment("GATEWAY_REQUEST", null, null, attemptId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_adjustment_scope_shape");
        insertAdjustment("GATEWAY_REQUEST", caseId, requestId, attemptId);

        assertThatThrownBy(() -> insertAdjustmentWithAmount("CASE_FULL", caseId, null, null,
                "0.00000000"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_adjustment_amount_nonzero");
        assertThatThrownBy(() -> insertAdjustmentWithAmount("CASE_FULL", caseId, null, null,
                "99999999999999.00000000"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void adjustmentRejectsForeignOrganizationParents() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reconciliation_adjustment(
                  org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                  adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                  created_by_member_id,reason_code,reason_note,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, otherOrgId, runId, caseId, "key-foreign-run-" + UUID.randomUUID(),
                "CASE_FULL", providerAccountId, "USD", "1.00000000", periodId, memberId, "R", "N"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_reconciliation_adjustment_run_org");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reconciliation_adjustment(
                  org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                  adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                  created_by_member_id,reason_code,reason_note,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, "key-foreign-account-" + UUID.randomUUID(),
                "CASE_FULL", foreignProviderAccountId(), "USD", "1.00000000", periodId,
                memberId, "R", "N"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fk_reconciliation_adjustment_account_org");
    }

    @Test
    void gatewayFinancialResolutionIsUniquePerRequestAndBounded() {
        var adjustmentId = insertAdjustment("GATEWAY_REQUEST", caseId, requestId, attemptId);
        insertResolution("STATEMENT_ADJUSTMENT_POSTED", "FINALIZED", adjustmentId);
        assertThatThrownBy(() -> insertResolution("NO_CHARGE_CONFIRMED", "RELEASED", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_gateway_financial_resolution_org_request");

        var secondRequest = insertGatewayChain("m15-schema-second-" + UUID.randomUUID())[0];
        assertThatThrownBy(() -> insertResolutionForRequest(secondRequest,
                "STATEMENT_ADJUSTMENT_POSTED", "NONE", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_financial_resolution_type_shape");
        assertThatThrownBy(() -> insertResolutionForRequest(secondRequest,
                "NO_CHARGE_CONFIRMED", "RELEASED", adjustmentId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_financial_resolution_type_shape");
        assertThatThrownBy(() -> insertResolutionForRequest(secondRequest,
                "NOT_A_RESOLUTION", "NONE", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_financial_resolution_");
        assertThatThrownBy(() -> insertResolutionForRequest(secondRequest,
                "NO_CHARGE_CONFIRMED", "FINALIZED", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_financial_resolution_reservation_outcome");
    }

    @Test
    void evidenceIsUniquePerRunKeyAndSupportsRunLevelNullCase() {
        insertEvidence(runId, null, "EXACT_PROVIDER_REQUEST", null,
                "exact:" + chargeId + ":" + requestId);
        assertThatThrownBy(() -> insertEvidence(runId, caseId, "AGGREGATE_SCOPE", null,
                "exact:" + chargeId + ":" + requestId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uq_reconciliation_evidence_org_run_key");
        // Run-level Gateway evidence without a fabricated case is legal.
        insertEvidence(runId, null, "GATEWAY_UNRESOLVED", "MISSING_GATEWAY_USAGE",
                "unresolved:" + requestId);
        assertThatThrownBy(() -> insertEvidence(runId, null, "AGGREGATE_SCOPE",
                "NOT_A_DIFFERENCE", "bad-difference"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_evidence_difference");
        assertThatThrownBy(() -> insertEvidence(runId, null, "NOT_A_MATCH", null, "bad-match"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_reconciliation_evidence_match_kind");
    }

    @Test
    void ledgerDirectSourceXorIncludesAdjustmentSource() {
        var adjustmentId = insertAdjustment("CASE_FULL", caseId, null, null);
        var postingId = insertPosting("RECONCILIATION_ADJUSTMENT:" + adjustmentId,
                "RECONCILIATION_ADJUSTMENT", adjustmentId);
        jdbc.update("""
                INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,
                  project_id,source_reconciliation_adjustment_id,created_at)
                VALUES (?,?,0,'ADJUSTMENT','1.00000000','USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, projectId, adjustmentId);

        // At most one direct source of the four is accepted.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,
                  project_id,source_charge_fact_id,source_reconciliation_adjustment_id,created_at)
                VALUES (?,?,0,'ADJUSTMENT','1.00000000','USD',?,?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, projectId, chargeId, adjustmentId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_ledger_entry_source_xor");
    }

    @Test
    void legacyProviderChargeBackfillIsExactlyOnceIdempotent() {
        var postingId = insertPosting("CHARGE:" + chargeId + ":ALLOCATION:1", "PROVIDER_CHARGE",
                chargeId);
        jdbc.update("""
                INSERT INTO ledger_entry(org_id,posting_id,entry_index,entry_type,amount,currency,
                  project_id,source_charge_fact_id,created_at)
                VALUES (?,?,0,'COST','10.00000000','USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, projectId, chargeId);
        assertThat(countDispositions(chargeId)).isZero();

        executeLegacyBackfill();
        assertThat(countDispositions(chargeId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT disposition,decision_source FROM provider_charge_disposition
                WHERE org_id=? AND charge_fact_id=?
                """, orgId, chargeId))
                .containsEntry("disposition", "DIRECT_PROVIDER_CHARGE")
                .containsEntry("decision_source", "LEGACY_POSTED");

        executeLegacyBackfill();
        assertThat(countDispositions(chargeId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long insertOrganization(String suffix) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        return lastId();
    }

    private long insertMember(long org, long user) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, user);
        return lastId();
    }

    private long otherOrgMemberId() {
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? LIMIT 1", Long.class, otherOrgId);
    }

    private long foreignProviderAccountId() {
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,status,
                  created_at,updated_at)
                VALUES (?,'OPENAI','another-foreign','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, otherOrgId);
        return lastId();
    }

    private long insertCharge(String suffix) {
        return insertChargeForOrg(orgId, suffix);
    }

    private long insertChargeForOrg(long org, String suffix) {
        jdbc.update("""
                INSERT INTO evidence(org_id,sha256,object_key,original_filename,media_type,
                  size_bytes,uploaded_by_member_id,storage_status,created_at,updated_at)
                VALUES (?,?,'m15/schema','schema.csv','text/csv',1,?,'AVAILABLE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256Of(suffix), memberIdOf(org));
        var evidenceId = lastId();
        jdbc.update("""
                INSERT INTO import_batch(org_id,evidence_id,provider_account_id,
                  expected_provider_code,source_type,parser_version,status,period_start,period_end,
                  created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,'OPENAI','FILE_EXPORT','v1','CONFIRMED',
                  '2026-08-01 00:00:00','2026-09-01 00:00:00',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, providerAccountIdOf(org), memberIdOf(org));
        var batchId = lastId();
        jdbc.update("""
                INSERT INTO import_attempt(import_batch_id,attempt_no,status,trigger_type,
                  available_at,lease_version,parser_version,started_at,finished_at,records_seen,
                  records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',UTC_TIMESTAMP(6),0,'v1',UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6),1,1,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = lastId();
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?", attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(import_attempt_id,record_index,record_locator,
                  raw_payload,normalize_status,created_at)
                VALUES (?,0,?,JSON_OBJECT(),'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, suffix);
        var rawId = lastId();
        jdbc.update("""
                INSERT INTO charge_fact(org_id,raw_record_id,fact_index,provider_code,
                  charge_category,amount,currency,period_start,period_end,review_status,
                  created_at)
                VALUES (?,?,0,'OPENAI','USAGE','10.00000000','USD',
                  '2026-08-10 00:00:00','2026-08-11 00:00:00','CLEAN',UTC_TIMESTAMP(6))
                """, org, rawId);
        return lastId();
    }

    private long memberIdOf(long org) {
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? LIMIT 1", Long.class, org);
    }

    private long providerAccountIdOf(long org) {
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? LIMIT 1", Long.class, org);
    }

    /** Returns {requestId, attemptId, usageFactId}. */
    private long[] insertGatewayChain(String suffix) {
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix, suffix);
        var serviceId = lastId();
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "model-" + suffix, suffix);
        var modelId = lastId();
        var providerCode = "MIMO-" + suffix;
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, suffix, "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "wire-" + suffix);
        var providerModelId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, suffix, suffix);
        var gatewayAccountId = lastId();
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, gatewayAccountId, providerModelId);
        var pricingVersionId = lastId();
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(11), serviceId, projectId, projectId);
        var gwCredentialId = lastId();
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixedId("gwr"), gwCredentialId, serviceId, projectId, projectId,
                modelId, digest(12), digest(13), periodId);
        var gwRequestId = lastId();
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,
                  provider_request_id,created_at)
                VALUES (?,?,1,?,?,?,?, 'COMPLETED',?,UTC_TIMESTAMP(6))
                """, orgId, gwRequestId, fixedId("grd"), gatewayAccountId, providerModelId,
                pricingVersionId, "prov-req-" + suffix);
        var gwAttemptId = lastId();
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                gwAttemptId, gwRequestId);
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,status,
                  usage_effective_at,usage_effective_at_source,pricing_version_id,currency,
                  observed_at,created_at)
                VALUES (?,?,?,1,'FINAL',UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, gwRequestId, gwAttemptId, pricingVersionId);
        var gwUsageFactId = lastId();
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                gwUsageFactId, gwRequestId);
        return new long[] {gwRequestId, gwAttemptId, gwUsageFactId};
    }

    private void insertDisposition(long charge, String disposition, String decisionSource,
            Long member, String reasonCode, String note) {
        jdbc.update("""
                INSERT INTO provider_charge_disposition(
                  org_id,charge_fact_id,disposition,decision_source,reconciliation_run_id,
                  reconciliation_case_id,decided_by_member_id,reason_code,resolution_note,
                  created_at)
                VALUES (?,?,?,?,NULL,NULL,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, charge, disposition, decisionSource, member, reasonCode, note);
    }

    private long insertAdjustment(String scope, Long caseRef, Long requestRef, Long attemptRef) {
        return insertAdjustmentWithAmount(scope, caseRef, requestRef, attemptRef, "1.00000000");
    }

    private long insertAdjustmentWithAmount(String scope, Long caseRef, Long requestRef,
            Long attemptRef, String amount) {
        jdbc.update("""
                INSERT INTO reconciliation_adjustment(
                  org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                  adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                  gateway_request_id,gateway_route_attempt_id,created_by_member_id,reason_code,
                  reason_note,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, caseRef, "adj-" + UUID.randomUUID(), scope, providerAccountId,
                "USD", amount, periodId, requestRef, attemptRef, memberId, "REASON", "note");
        return lastId();
    }

    private void insertResolution(String resolutionType, String reservationOutcome,
            Long adjustmentId) {
        insertResolutionForRequest(requestId, resolutionType, reservationOutcome, adjustmentId);
    }

    private void insertResolutionForRequest(long request, String resolutionType,
            String reservationOutcome, Long adjustmentId) {
        jdbc.update("""
                INSERT INTO gateway_financial_resolution(
                  org_id,reconciliation_run_id,reconciliation_case_id,request_id,route_attempt_id,
                  usage_fact_id,gateway_settlement_id,statement_charge_fact_id,
                  reconciliation_adjustment_id,reservation_id,resolution_type,reservation_outcome,
                  resolved_by_member_id,reason_code,reason_note,resolved_at,created_at)
                VALUES (?,?,?,?,?,?,NULL,NULL,?,NULL,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, request, attemptId, usageFactId, adjustmentId,
                resolutionType, reservationOutcome, memberId, "REASON", "note");
    }

    private void insertEvidence(long run, Long caseRef, String matchKind, String differenceKind,
            String evidenceKey) {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(
                  org_id,reconciliation_run_id,reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,difference_kind,charge_fact_id,
                  gateway_request_id,gateway_route_attempt_id,gateway_usage_fact_id,
                  gateway_settlement_id,correction_group_id,reconciliation_adjustment_id,
                  gateway_financial_resolution_id,ledger_posting_id,provider_request_id,
                  external_amount,internal_amount,difference_amount,created_at)
                VALUES (?,?,?,?,?,?,?,?,
                  NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,
                  UTC_TIMESTAMP(6))
                """, orgId, run, caseRef, evidenceKey, providerAccountId, "USD", matchKind,
                differenceKind);
    }

    private long insertPosting(String postingKey, String sourceType, long sourceId) {
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posted_by_member_id,posted_at,
                  created_at)
                VALUES (?,?,?,?,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, postingKey, sourceType, sourceId, periodId, memberId);
        return lastId();
    }

    private long countDispositions(long charge) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, charge);
    }

    /**
     * Re-executes the exact legacy backfill statement shipped in V23 so the
     * test proves the shipped SQL is idempotent against live data.
     */
    private void executeLegacyBackfill() {
        jdbc.execute("""
                INSERT INTO provider_charge_disposition(
                    org_id,charge_fact_id,disposition,decision_source,created_at)
                SELECT lp.org_id,lp.source_id,'DIRECT_PROVIDER_CHARGE','LEGACY_POSTED',lp.posted_at
                FROM ledger_posting lp
                JOIN charge_fact cf
                  ON cf.id=lp.source_id AND cf.org_id=lp.org_id
                WHERE lp.source_type='PROVIDER_CHARGE'
                  AND NOT EXISTS (
                    SELECT 1 FROM provider_charge_disposition pcd
                    WHERE pcd.org_id=lp.org_id AND pcd.charge_fact_id=lp.source_id)
                """);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static String sha256Of(String seed) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }

    private static byte[] digest(int seed) {
        var result = new byte[32];
        for (var i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }

    private static String fixedId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "") + "00000";
    }
}
