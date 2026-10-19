package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves V24 dispatch-time endpoint authority: candidates resolve base URL
 * and protocol from the ACTIVE connection profile, and later catalog edits
 * cannot move live dispatch.
 */
@SpringBootTest
@Tag("integration")
class ConnectionProfileEndpointAuthorityIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoutingPolicyResolver resolver;

    private SeededEnv env;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void candidateEndpointComesFromActiveProfileNotCatalog() {
        env = GatewayTestFixture.seed(jdbc, "profile-authority-" + System.nanoTime(),
                HMAC_KEY, rawKey());
        var policy = resolver.resolve(env.orgId(), env.projectId(), env.modelId(), Instant.now());
        assertThat(policy.candidates()).isNotEmpty();
        var candidate = policy.candidates().get(0);
        assertThat(candidate.providerConnectionProfileId()).isPositive();
        assertThat(candidate.baseUrl()).isEqualTo(GatewayTestFixture.DEFAULT_BASE_URL);
        assertThat(candidate.completionPath()).isEqualTo("/chat/completions");
        assertThat(candidate.protocolCode()).isEqualTo("MIMO_CHAT_COMPLETIONS");

        jdbc.update("UPDATE provider_catalog SET base_url='https://moved.example.test/v9'"
                + " WHERE provider_code='MIMO'");
        var resolved = resolver.resolve(env.orgId(), env.projectId(), env.modelId(), Instant.now());
        assertThat(resolved.candidates().get(0).baseUrl())
                .isEqualTo(GatewayTestFixture.DEFAULT_BASE_URL);
    }

    @Test
    void candidateWithoutActiveProfileIsNotRoutable() {
        env = GatewayTestFixture.seed(jdbc, "profile-required-" + System.nanoTime(),
                HMAC_KEY, rawKey());
        jdbc.update("UPDATE provider_connection_profile SET status='RETIRED',retired_at=UTC_TIMESTAMP(6)"
                + " WHERE org_id=? AND provider_account_id=? AND status='ACTIVE'",
                env.orgId(), env.providerAccountId());
        var policy = resolver.resolve(env.orgId(), env.projectId(), env.modelId(), Instant.now());
        assertThat(policy.candidates()).isEmpty();
    }

    @Test
    void routeResolutionUsesTheNewActiveProfileAndKeepsTheOldVersionAddress() {
        env = GatewayTestFixture.seed(jdbc, "profile-version-route-" + System.nanoTime(),
                HMAC_KEY, rawKey());
        var oldProfile = jdbc.queryForMap("""
                SELECT id,base_url,completion_path,models_path,auth_type,auth_header_name,
                  network_policy,user_agent,connect_timeout_ms,response_timeout_ms
                FROM provider_connection_profile WHERE org_id=? AND provider_account_id=? AND status='ACTIVE'
                """, env.orgId(), env.providerAccountId());
        var oldProfileId = ((Number) oldProfile.get("id")).longValue();
        var nextBaseUrl = "https://mimo-version-2.example.test/v2";

        jdbc.update("UPDATE provider_connection_profile SET status='RETIRED',retired_at=UTC_TIMESTAMP(6)"
                + " WHERE id=? AND status='ACTIVE'", oldProfileId);
        jdbc.update("""
                INSERT INTO provider_connection_profile(
                  org_id,provider_account_id,version,connection_kind,template_code,protocol_code,base_url,
                  completion_path,models_path,auth_type,auth_header_name,network_policy,user_agent,
                  connect_timeout_ms,response_timeout_ms,status,created_at,activated_at)
                SELECT org_id,provider_account_id,2,connection_kind,template_code,protocol_code,?,
                  completion_path,models_path,auth_type,auth_header_name,network_policy,user_agent,
                  connect_timeout_ms,response_timeout_ms,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
                FROM provider_connection_profile WHERE id=?
                """, nextBaseUrl, oldProfileId);

        var newProfileId = jdbc.queryForObject("""
                SELECT id FROM provider_connection_profile
                WHERE org_id=? AND provider_account_id=? AND status='ACTIVE'
                """, Long.class, env.orgId(), env.providerAccountId());
        var policy = resolver.resolve(env.orgId(), env.projectId(), env.modelId(), Instant.now());

        assertThat(newProfileId).isNotEqualTo(oldProfileId);
        assertThat(policy.candidates()).hasSize(1);
        assertThat(policy.candidates().get(0).providerConnectionProfileId()).isEqualTo(newProfileId);
        assertThat(policy.candidates().get(0).baseUrl()).isEqualTo(nextBaseUrl);
        assertThat(jdbc.queryForObject("SELECT base_url FROM provider_connection_profile WHERE id=?",
                String.class, oldProfileId)).isEqualTo(GatewayTestFixture.DEFAULT_BASE_URL);
        assertThat(jdbc.queryForObject("SELECT status FROM provider_connection_profile WHERE id=?",
                String.class, oldProfileId)).isEqualTo("RETIRED");
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
