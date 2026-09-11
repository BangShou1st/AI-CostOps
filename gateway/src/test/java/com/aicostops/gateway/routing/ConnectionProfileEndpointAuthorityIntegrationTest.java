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

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }
}
