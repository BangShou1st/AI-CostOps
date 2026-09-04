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

/** M13-A V20 contract tests against real MySQL constraints. */
@SpringBootTest
@Tag("integration")
class GatewayM13UsageSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void migrationCreatesUsageTablesAndCurrentPointer() {
        assertThat(tables()).contains("gateway_usage_fact", "gateway_usage_dimension");
        assertThat(columnType("gateway_usage_dimension", "quantity")).isEqualTo("decimal(30,8)");
        assertThat(columnType("gateway_usage_fact", "final_slot")).isEqualTo("tinyint");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='gateway_request'
                  AND column_name='current_usage_fact_id'
                """, Integer.class)).isOne();
        assertThat(indexes("gateway_usage_fact"))
                .contains("uq_gateway_usage_fact_id_org", "uq_gateway_usage_fact_request_sequence",
                        "uq_gateway_usage_fact_request_final");
        assertThat(indexes("gateway_usage_dimension"))
                .contains("uq_gateway_usage_dimension_id_org", "uq_gateway_usage_dimension_fact_code");
    }

    @Test
    void usageFactsEnforceStatusDimensionQuantityProvenanceAndCurrencyChecks() {
        var fixture = insertFixture("checks");

        assertThatThrownBy(() -> insertFact(fixture, 1, "NOT_A_STATUS", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_usage_fact_status");
        assertThatThrownBy(() -> insertFact(fixture, 1, "FINAL", "US"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_usage_fact_currency");

        var factId = insertFact(fixture, 1, "FINAL", null);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_usage_dimension(
                  org_id,usage_fact_id,dimension_code,quantity,provenance)
                VALUES (?,?, 'NOT_A_DIMENSION', 1, 'PROVIDER_FINAL')
                """, fixture.orgId(), factId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_usage_dimension_code");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_usage_dimension(
                  org_id,usage_fact_id,dimension_code,quantity,provenance)
                VALUES (?,?, 'INPUT_TOKEN', -1, 'PROVIDER_FINAL')
                """, fixture.orgId(), factId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_usage_dimension_quantity");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO gateway_usage_dimension(
                  org_id,usage_fact_id,dimension_code,quantity,provenance)
                VALUES (?,?, 'OUTPUT_TOKEN', 1, 'NOT_A_PROVENANCE')
                """, fixture.orgId(), factId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_gateway_usage_dimension_provenance");
    }

    @Test
    void sameOrgForeignKeysAndFinalUniquenessAreEnforced() {
        var fixture = insertFixture("foreign-keys");
        var otherOrg = insertOtherOrganization("other");

        assertThatThrownBy(() -> insertFact(
                new Fixture(otherOrg, fixture.requestId(), fixture.attemptId(), fixture.pricingVersionId()),
                1, "FINAL", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_gateway_usage_fact_request_org");

        var first = insertFact(fixture, 1, "FINAL", null);
        assertThatThrownBy(() -> insertFact(fixture, 2, "FINAL", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_gateway_usage_fact_request_final");

        assertThat(jdbc.update("""
                UPDATE gateway_request SET current_usage_fact_id=? WHERE id=? AND org_id=?
                """, first, fixture.requestId(), fixture.orgId())).isOne();
    }

    private long insertFact(Fixture fixture, int sequence, String status, String currency) {
        jdbc.update("""
                INSERT INTO gateway_usage_fact(
                  org_id,request_id,route_attempt_id,sequence,status,supersedes_usage_fact_id,
                  provider_request_id,usage_effective_at,usage_effective_at_source,
                  pricing_version_id,currency,safe_provider_metadata_json,observed_at,created_at)
                VALUES (?,?,?,?,?,NULL,NULL,UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,COALESCE(?, 'USD'),NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), fixture.requestId(), fixture.attemptId(), sequence, status,
                fixture.pricingVersionId(), currency);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Fixture insertFixture(String suffix) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M13 " + suffix, "m13-schema-" + suffix + "-" + System.nanoTime());
        var orgId = lastId();
        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),
                  DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = lastId();
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'M13 Schema Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "m13-proj-" + System.nanoTime());
        var projectId = lastId();
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'M13 Schema Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "m13-svc-" + System.nanoTime());
        var serviceId = lastId();
        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,max_output_tokens,
                  created_at,updated_at)
                VALUES (?, 'M13 Schema Model','ACTIVE',JSON_OBJECT(),128,1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "m13-model-" + System.nanoTime());
        var modelId = lastId();
        jdbc.update("""
                INSERT INTO provider_catalog(
                  provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at)
                VALUES ('MIMO','MiMo','MIMO','https://api.xiaomimimo.com/v1','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO provider_model(
                  provider_code,model_id,provider_model_name,status,routing_eligible,capabilities_json,
                  created_at,updated_at)
                VALUES ('MIMO',?,'mimo-v2.5-pro','ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, modelId);
        var providerModelId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,'MIMO','M13 Account',?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "m13-acct-" + System.nanoTime());
        var accountId = lastId();
        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,effective_from,status,
                  created_at,activated_at)
                VALUES (?,?,?,1,'USD',UTC_TIMESTAMP(6),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, accountId, providerModelId);
        var pricingVersionId = lastId();
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  budget_enforcement_mode,status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, uniquePrefix(), digest(1), serviceId, projectId, projectId);
        var credentialId = lastId();
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,logical_model_id,
                  api_surface,idempotency_key_digest,request_fingerprint,request_hmac_version,state,
                  billing_period_id,current_route_attempt_id,current_usage_fact_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,'VALIDATED',?,NULL,NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "gwr_m13_" + System.nanoTime(), credentialId, serviceId, projectId,
                projectId, modelId, digest(2), digest(3), periodId);
        var requestId = lastId();
        jdbc.update("""
                INSERT INTO gateway_route_attempt(
                  org_id,request_id,attempt_no,route_decision_id,provider_account_id,provider_model_id,
                  pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,'PLANNED',UTC_TIMESTAMP(6))
                """, orgId, requestId, "grd_m13_" + System.nanoTime(), accountId, providerModelId,
                pricingVersionId);
        var attemptId = lastId();
        return new Fixture(orgId, requestId, attemptId, pricingVersionId);
    }

    private long insertOtherOrganization(String suffix) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M13 Other " + suffix, "m13-other-" + suffix + "-" + System.nanoTime());
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private List<String> tables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
                """, String.class);
    }

    private List<String> indexes(String table) {
        return jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private static String uniquePrefix() {
        var alphabet = "0123456789abcdefghjkmnpqrstvwxyz";
        var value = System.nanoTime();
        var result = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            result.append(alphabet.charAt((int) Math.floorMod(value, alphabet.length())));
            value = value / alphabet.length() + 17;
        }
        return result.toString();
    }

    private record Fixture(long orgId, long requestId, long attemptId, long pricingVersionId) {
    }
}
