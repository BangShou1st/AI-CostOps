package com.aicostops.gatewaysettlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gatewaysettlement.application.GatewaySettlementDiscoveryService;
import com.aicostops.gatewaysettlement.domain.GatewaySettlementStatus;
import com.aicostops.gatewaysettlement.infrastructure.GatewaySettlementMapper;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Discovery convergence tests use the V21 tables on MySQL 8.4. */
@SpringBootTest
@Tag("integration")
class GatewaySettlementDiscoveryIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired GatewaySettlementDiscoveryService discovery;
    @Autowired GatewaySettlementMapper settlements;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void discoversOnlyCurrentFinalUsageAndConvergesDuplicateDiscovery() {
        var fixture = fixture();
        insertUsage(fixture, "FINAL");
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                lastId(), fixture.requestId());

        var first = discovery.discover(fixture.orgId(), 10);
        var second = discovery.discover(fixture.orgId(), 10);

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().status()).isEqualTo(GatewaySettlementStatus.PENDING);
        assertThat(first.getFirst().settlementKey())
                .isEqualTo("GATEWAY_REQUEST:" + fixture.publicRequestId());
        assertThat(second).isEmpty();
        assertThat(settlements.selectByRequestId(fixture.orgId(), fixture.requestId()).id())
                .isEqualTo(first.getFirst().id());
        assertThat(discovery.workIds(fixture.orgId())).containsExactly(first.getFirst().id());
    }

    @Test
    void ignoresIncompleteAndNonCurrentUsageFacts() {
        var fixture = fixture();
        insertUsage(fixture, "INCOMPLETE");
        var incompleteId = lastId();
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                incompleteId, fixture.requestId());

        assertThat(discovery.discover(fixture.orgId(), 10)).isEmpty();
        assertThat(settlements.selectByRequestId(fixture.orgId(), fixture.requestId())).isNull();
    }

    private void insertUsage(Fixture fixture, String status) {
        jdbc.update("""
                INSERT INTO gateway_usage_fact(
                  org_id,request_id,route_attempt_id,sequence,status,supersedes_usage_fact_id,
                  provider_request_id,usage_effective_at,usage_effective_at_source,
                  pricing_version_id,currency,safe_provider_metadata_json,observed_at,created_at)
                VALUES (?,?,?,?,?,NULL,NULL,UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), fixture.requestId(), fixture.attemptId(),
                fixture.nextSequence(), status, fixture.pricingVersionId());
    }

    private Fixture fixture() {
        var suffix = "settlement-discovery-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?, 'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        var orgId = lastId();
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,close_generation,
                  version,created_at,updated_at)
                VALUES (?, '2026-08-01 00:00:00','2026-09-01 00:00:00','OPEN',0,0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = lastId();
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?, 'Discovery Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceId = lastId();
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,max_output_tokens,
                  created_at,updated_at)
                VALUES (?, 'Discovery Model','ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "model-" + suffix);
        var modelId = lastId();
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?, ?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "MIMO-" + suffix, "MiMo", "MIMO", "https://provider.invalid");
        var providerCode = "MIMO-" + suffix;
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "wire-" + suffix);
        var providerModelId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,external_account_ref,
                  status,metadata_json,created_at,updated_at)
                VALUES (?,?,? ,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, "Discovery Account", suffix);
        var providerAccountId = lastId();
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = lastId();
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,status,
                  created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,0,'PROJECT',0,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(1), serviceId);
        var credentialId = lastId();
        var publicRequestId = fixedId("gwr");
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, publicRequestId, credentialId, serviceId, modelId,
                digest(2), digest(3), periodId);
        var requestId = lastId();
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,'COMPLETED',UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedId("grd"), providerAccountId, providerModelId,
                pricingVersionId);
        var attemptId = lastId();
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        return new Fixture(orgId, requestId, attemptId, pricingVersionId, publicRequestId, 1);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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

    private record Fixture(long orgId, long requestId, long attemptId, long pricingVersionId,
            String publicRequestId, int nextSequence) {
    }
}
