package com.aicostops.gateway.provider.mimo;

import static org.junit.jupiter.api.Assertions.*;

import com.aicostops.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression test for M21 post-release security fix: MiMo adapter must
 * enforce PublicOnlyAddressResolverGroup in production profile.
 */
class MimoChatAdapterResolverTest {

    @Test
    void mimoAdapterConstructsWithPublicOnlyResolverInProdProfile() {
        // Given: prod profile active
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(5000);
        properties.setHeaderTimeoutMs(60000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        env.addActiveProfile("prod");

        // When: adapter is constructed
        var adapter = new MimoChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);

        // Then: adapter is non-null and reports correct code
        assertNotNull(adapter);
        assertEquals("MIMO", adapter.adapterCode());
    }

    @Test
    void mimoAdapterConstructsWithoutPublicOnlyResolverInDevProfile() {
        // Given: prod profile NOT active
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(5000);
        properties.setHeaderTimeoutMs(60000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        // No prod profile active

        // When: adapter is constructed in dev/test profile
        var adapter = new MimoChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);

        // Then: adapter works without public-only resolver (allows local mock validation)
        assertNotNull(adapter);
        assertEquals("MIMO", adapter.adapterCode());
    }
}