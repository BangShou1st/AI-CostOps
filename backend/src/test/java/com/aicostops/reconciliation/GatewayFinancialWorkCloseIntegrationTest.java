package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.blockers.GatewayFinancialWorkBlockerProvider;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M11 close-safety on real MySQL: possible-billable Gateway requests after
 * DISPATCH_INTENT block normal Close; pre-dispatch or period-unscoped
 * requests do not.
 */
@SpringBootTest
@Tag("integration")
class GatewayFinancialWorkCloseIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayFinancialWorkBlockerProvider provider;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void dispatchIntentRequestBlocksClose() {
        var fixture = insertFixture();
        var requestId = insertGatewayRequest(fixture, "DISPATCH_INTENT", digest(1), digest(2));

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
        assertThat(result.summary()).containsEntry("billingPeriodId", fixture.periodId());
        assertThat(requestId).isPositive();
    }

    @Test
    void transportCompletedAndPostDispatchFailuresBlockClose() {
        var fixture = insertFixture();
        insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(3), digest(4));
        insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(5), digest(6));

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(2);
    }

    @Test
    void validatedRequestDoesNotBlockClose() {
        var fixture = insertFixture();
        insertGatewayRequest(fixture, "VALIDATED", digest(7), digest(8));

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void requestWithoutBillingPeriodDoesNotBlockClose() {
        var fixture = insertFixture();
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,'VALIDATED',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "gwr_123e4567-e89b-42d3-a456-426614174000",
                fixture.credentialId(), fixture.serviceIdentityId(), fixture.modelId(),
                digest(9), digest(10));

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    private CloseBlockerContext context(Fixture fixture) {
        return new CloseBlockerContext(fixture.orgId(), fixture.periodId(),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
    }

    private long insertGatewayRequest(Fixture fixture, String state, byte[] idem, byte[] fp) {
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "gwr_" + uuid(fixture.orgId() + state.hashCode()),
                fixture.credentialId(), fixture.serviceIdentityId(), fixture.modelId(),
                idem, fp, state, fixture.periodId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Fixture insertFixture() {
        var suffix = "gw-close-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M11 " + suffix, suffix);
        var orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,closing_started_at,
                  closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                  'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Close Test Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceIdentityId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,'Close Test Model','ACTIVE',JSON_OBJECT(),8192,131072,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "close-model-" + suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,0,'PROJECT',0,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, "0123456789ab", digest(11), serviceIdentityId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Fixture(orgId, periodId, serviceIdentityId, modelId, credentialId);
    }

    private static String uuid(long seed) {
        var digits = Math.floorMod(seed, 1_000_000_000_000L);
        return "123e4567-e89b-42d3-a456-" + String.format("%012d", digits);
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((seed + i) % 251);
        }
        return bytes;
    }

    private record Fixture(long orgId, long periodId, long serviceIdentityId, long modelId,
            long credentialId) {
    }
}