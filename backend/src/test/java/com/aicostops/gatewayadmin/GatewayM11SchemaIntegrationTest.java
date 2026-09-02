package com.aicostops.gatewayadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V18 contract for the M11 Gateway edge foundation schema (AIC-092 wave M11).
 * Real MySQL 8.4: the empty database migrates exactly through V18, the eleven
 * M11 tables carry the exact AIC-092 constraints, and no M12/M13/Ledger table
 * leaks into the wave.
 */
@SpringBootTest
@Tag("integration")
class GatewayM11SchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void migrationCreatesExactlyTheElevenM11Tables() {
        assertThat(queryTables())
                .contains(
                        "service_identity", "gateway_credential", "gateway_credential_model",
                        "provider_credential", "provider_catalog", "model_catalog",
                        "provider_model", "pricing_version", "pricing_rate",
                        "gateway_request", "gateway_route_attempt");

        assertThat(queryTables())
                .doesNotContain(
                        "budget_reservation", "gateway_usage_fact", "gateway_usage_dimension",
                        "gateway_settlement", "routing_policy", "routing_policy_candidate");
    }

    @Test
    void emptyDatabaseMigratesThroughV18AndV1TablesRemainUsable() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class)).isEqualTo(18);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '18' AND success = 1",
                Integer.class)).isEqualTo(1);

        // V1 identity tables and the V13 Ledger tables remain queryable.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM organization", Integer.class))
                .isZero();
        assertThat(queryTables()).contains("ledger_posting", "ledger_entry");
    }

    @Test
    void credentialPrefixIsUnique() {
        var fixture = insertFixture("prefix");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,0,'PROJECT',0,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, fixture.orgId(), fixture.prefix(), digest(21), fixture.serviceIdentityId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_gateway_credential_prefix");
    }

    @Test
    void gatewayCredentialPrincipalXorIsEnforced() {
        var fixture = insertFixture("principal-xor");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'HUMAN_MEMBER',NULL,NULL,0,'PROJECT',0,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, fixture.orgId(), uniquePrefix("principal-xor"), digest(22)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_credential_principal_xor");
    }

    @Test
    void gatewayRequestPrincipalXorIsEnforced() {
        var fixture = insertFixture("request-xor");

        assertThatThrownBy(() -> insertGatewayRequest(fixture,
                "gwr_22222222-2222-4222-8222-222222222222",
                "SERVICE", fixture.memberId(), null, digest(23), digest(24)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_request_principal_xor");
    }

    @Test
    void credentialModelRelationIsExplicitCompositeKeyAndStatusChecked() {
        var fixture = insertFixture("cred-model");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
                VALUES (?,?,?,'NOT_A_STATUS',UTC_TIMESTAMP(6))
                """, fixture.credentialId(), fixture.orgId(), fixture.modelId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_credential_model_status");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, fixture.credentialId(), fixture.orgId(), fixture.modelId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publicRequestIdIsUnique() {
        var fixture = insertFixture("public-id");
        var publicId = "gwr_33333333-3333-4333-8333-333333333333";
        insertGatewayRequest(fixture, publicId, "SERVICE", null, fixture.serviceIdentityId(),
                digest(25), digest(26));

        assertThatThrownBy(() -> insertGatewayRequest(fixture, publicId, "SERVICE", null,
                fixture.serviceIdentityId(), digest(27), digest(28)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_gateway_request_public");
    }

    @Test
    void idempotencyIdentityIsUniquePerOrgAndCredential() {
        var fixture = insertFixture("idem");
        insertGatewayRequest(fixture, "gwr_44444444-4444-4444-8444-444444444444", "SERVICE", null,
                fixture.serviceIdentityId(), fixture.requestDigest(), digest(26));

        assertThatThrownBy(() -> insertGatewayRequest(fixture,
                "gwr_45454545-4545-4454-8454-454545454545", "SERVICE", null,
                fixture.serviceIdentityId(), fixture.requestDigest(), digest(27)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_gateway_request_idem");
    }

    @Test
    void requestStateCheckRejectsUnknownState() {
        var fixture = insertFixture("request-state");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,current_route_attempt_id,
                  current_usage_fact_id,created_at,validated_at,dispatch_intent_at,terminal_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,'NOT_A_STATE',?,
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL,UTC_TIMESTAMP(6))
                """, fixture.orgId(), "gwr_55555555-5555-4555-8555-555555555555",
                fixture.credentialId(), fixture.serviceIdentityId(), fixture.modelId(),
                digest(28), digest(29), fixture.periodId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_request_state");
    }

    @Test
    void routeAttemptNoIsUniquePerRequest() {
        var fixture = insertFixture("attempt-no");
        var requestId = insertRequestAndRoute(fixture, "gwr_66666666-6666-4666-8666-666666666666");

        assertThatThrownBy(() -> jdbc.update(insertRouteSql(), fixture.orgId(), requestId, 1,
                "grd_77777777-7777-4777-8777-777777777777", fixture.providerAccountId(),
                fixture.providerModelId(), fixture.pricingVersionId(), "PLANNED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_gateway_route_attempt_attempt");
    }

    @Test
    void routeDecisionIdIsUniquePerOrg() {
        var fixture = insertFixture("decision-id");
        var firstRequestId = insertGatewayRequest(fixture,
                "gwr_88888888-8888-4888-8888-888888888888", "SERVICE", null,
                fixture.serviceIdentityId(), digest(80), digest(81));
        var firstDecisionId = "grd_88888888-8888-4888-8888-888888888888";
        jdbc.update(insertRouteSql(), fixture.orgId(), firstRequestId, 1, firstDecisionId,
                fixture.providerAccountId(), fixture.providerModelId(), fixture.pricingVersionId(),
                "PLANNED");

        var thirdRequestId = insertGatewayRequest(fixture,
                "gwr_99999999-9999-4999-8999-999999999999", "SERVICE", null,
                fixture.serviceIdentityId(), digest(82), digest(83));

        // A second request reusing the first request's route decision must
        // fail on the route-decision uniqueness, not on attempt uniqueness.
        assertThatThrownBy(() -> jdbc.update(insertRouteSql(), fixture.orgId(), thirdRequestId, 1,
                firstDecisionId, fixture.providerAccountId(),
                fixture.providerModelId(), fixture.pricingVersionId(), "PLANNED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_gateway_route_attempt_decision");
    }

    @Test
    void routeStatusCheckRejectsUnknownState() {
        var fixture = insertFixture("route-state");
        var requestId = insertRequestAndRoute(fixture, "gwr_abababab-abab-4bab-8bab-abababababab");

        assertThatThrownBy(() -> jdbc.update(insertRouteSql(), fixture.orgId(), requestId, 2,
                "grd_cbcbcbcb-cbcb-4cbc-8cbc-cbcbcbcbcbcb", fixture.providerAccountId(),
                fixture.providerModelId(), fixture.pricingVersionId(), "NOT_A_STATE"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_route_attempt_status");
    }

    @Test
    void sameOrgForeignKeyViolationFails() {
        var fixture = insertFixture("same-org");
        var otherOrgId = insertOtherOrganization("same-org-other");
        var otherAccountId = jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, otherOrgId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO provider_credential(
                  org_id,provider_account_id,credential_type,ciphertext,nonce,
                  encryption_key_version,safe_label,status,predecessor_credential_id,
                  created_at,rotated_at,revoked_at)
                VALUES (?,?,'API_KEY',?,'123456789012',1,'cross-org','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, fixture.orgId(), otherAccountId, digest(30)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_provider_credential_account_org");
    }

    @Test
    void currentRouteAttemptConveniencePointerIsAttached() {
        var fixture = insertFixture("route-pointer");
        var requestId = insertRequestAndRoute(fixture, "gwr_12121212-1212-4121-8212-121212121212");
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);

        jdbc.update("""
                UPDATE gateway_request SET current_route_attempt_id=?, updated_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, attemptId, requestId);

        assertThat(jdbc.queryForObject(
                "SELECT current_route_attempt_id FROM gateway_request WHERE id=?",
                Long.class, requestId)).isEqualTo(attemptId);
    }

    @Test
    void exactColumnTypesMatchAic092() {
        assertThat(columnType("gateway_credential", "credential_prefix")).isEqualTo("char(12)");
        assertThat(columnType("gateway_credential", "secret_digest")).isEqualTo("binary(32)");
        assertThat(columnType("gateway_credential", "secret_digest_version")).isEqualTo("smallint unsigned");
        assertThat(columnType("gateway_request", "public_request_id")).isEqualTo("char(40)");
        assertThat(columnType("gateway_request", "idempotency_key_digest")).isEqualTo("binary(32)");
        assertThat(columnType("gateway_request", "request_fingerprint")).isEqualTo("binary(32)");
        assertThat(columnType("gateway_request", "request_hmac_version")).isEqualTo("smallint unsigned");
        assertThat(columnType("provider_credential", "ciphertext")).isEqualTo("varbinary(2048)");
        assertThat(columnType("provider_credential", "nonce")).isEqualTo("binary(12)");
        assertThat(columnType("pricing_version", "currency")).isEqualTo("char(3)");
        assertThat(columnType("pricing_rate", "unit_price")).isEqualTo("decimal(20,8)");
        assertThat(columnType("pricing_rate", "unit_quantity")).isEqualTo("bigint");
        assertThat(columnType("gateway_request", "created_at")).isEqualTo("datetime(6)");
        assertThat(columnType("gateway_request", "updated_at")).isEqualTo("datetime(6)");
        assertThat(columnType("gateway_route_attempt", "route_decision_id")).isEqualTo("char(40)");
        assertThat(columnType("pricing_rate", "id")).isEqualTo("bigint");
    }

    private List<String> queryTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private Fixture insertFixture(String prefix) {
        var suffix = prefix + "-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M11 " + suffix, suffix);
        var orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.test", "M11 User");
        var userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,employee_no,default_cost_center_id,status,joined_at)
                VALUES (?,?,NULL,NULL,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        var memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,closing_started_at,
                  closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                  'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,'MIMO',?,'MIMO-ACCT','ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,'M11 Logical Model','ACTIVE',JSON_OBJECT(),8192,131072,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "default-chat-" + suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO provider_catalog(
                  provider_code,name,adapter_code,base_url,status,capabilities_json,
                  created_at,updated_at)
                VALUES ('MIMO','MiMo','MIMO','https://api.xiaomimimo.com/v1','ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO provider_model(
                  provider_code,model_id,provider_model_name,status,routing_eligible,
                  capabilities_json,created_at,updated_at)
                VALUES ('MIMO',?,'mimo-v2.5-pro','ACTIVE',TRUE,JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, modelId);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'M11 Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceIdentityId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        var credentialPrefix = uniquePrefix(prefix);
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,0,'PROJECT',0,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, credentialPrefix, digest(7), serviceIdentityId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, credentialId, orgId, modelId);

        jdbc.update("""
                INSERT INTO provider_credential(
                  org_id,provider_account_id,credential_type,ciphertext,nonce,
                  encryption_key_version,safe_label,status,predecessor_credential_id,
                  created_at,rotated_at,revoked_at)
                VALUES (?,?,'API_KEY',?,'123456789012',1,'M11 label','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, orgId, providerAccountId, digest(13));

        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,
                  effective_from,effective_to,status,created_at,activated_at,retired_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00.000000',NULL,'ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'INPUT_TOKEN',1000000,'1.00000000')
                """, orgId, pricingVersionId);
        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'OUTPUT_TOKEN',1000000,'1.00000000')
                """, orgId, pricingVersionId);

        return new Fixture(orgId, memberId, periodId, providerAccountId, serviceIdentityId,
                credentialId, modelId, providerModelId, pricingVersionId, credentialPrefix, digest(42));
    }

    private long insertOtherOrganization(String prefix) {
        var suffix = prefix + "-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M11 " + suffix, suffix);
        var orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,'MIMO',?,'MIMO-OTHER','ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix);
        return orgId;
    }

    private long insertGatewayRequest(Fixture fixture, String publicId, String principalType,
            Long memberId, Long serviceIdentityId, byte[] idemDigest, byte[] fingerprint) {
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,current_route_attempt_id,
                  current_usage_fact_id,created_at,validated_at,dispatch_intent_at,terminal_at,updated_at)
                VALUES (?,?,?,'SERVICE',?,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,'DISPATCH_INTENT',?,
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL,UTC_TIMESTAMP(6))
                """, fixture.orgId(), publicId, fixture.credentialId(), memberId, serviceIdentityId,
                fixture.modelId(), idemDigest, fingerprint, null);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRequestAndRoute(Fixture fixture, String publicId) {
        var requestId = insertGatewayRequest(fixture, publicId, "SERVICE", null,
                fixture.serviceIdentityId(), digest(publicId.hashCode()), digest(31));
        var routeDecisionId = "grd_" + publicId.substring(4);
        jdbc.update(insertRouteSql(), fixture.orgId(), requestId, 1, routeDecisionId,
                fixture.providerAccountId(), fixture.providerModelId(), fixture.pricingVersionId(),
                "PLANNED");
        return requestId;
    }

    private static String insertRouteSql() {
        return """
                INSERT INTO gateway_route_attempt(
                  org_id,request_id,attempt_no,route_decision_id,routing_policy_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,
                  safety_reason_code,provider_request_id,created_at,dispatch_intent_at,completed_at)
                VALUES (?,?,?,?,NULL,?,?,?,?,NULL,NULL,UTC_TIMESTAMP(6),NULL,NULL)
                """;
    }

    private static String uniquePrefix(String seed) {
        var alphabet = "0123456789abcdefghjkmnpqrstvwxyz";
        var hash = seed.hashCode();
        var builder = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            builder.append(alphabet.charAt(Math.floorMod(hash, alphabet.length())));
            hash = hash * 31 + 7;
        }
        return builder.toString().toLowerCase();
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((seed + i) % 251);
        }
        return bytes;
    }

    private record Fixture(
            long orgId,
            long memberId,
            long periodId,
            long providerAccountId,
            long serviceIdentityId,
            long credentialId,
            long modelId,
            long providerModelId,
            long pricingVersionId,
            String prefix,
            byte[] requestDigest) {
    }
}