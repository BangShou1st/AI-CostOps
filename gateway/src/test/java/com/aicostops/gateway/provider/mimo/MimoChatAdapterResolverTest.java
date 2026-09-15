package com.aicostops.gateway.provider.mimo;

import static org.junit.jupiter.api.Assertions.*;

import com.aicostops.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * M21 post-release SSRF regression test for MiMo adapter.
 *
 * <p>Verifies that the MiMo adapter wires PublicOnlyAddressResolverGroup
 * into its HttpClient when the production profile is active, enforcing the
 * frozen M18 DNS-rebinding / SSRF contract.
 *
 * <p>Mutation proof: if the resolver wiring line is removed from
 * MimoChatAdapter, this test FAILS because the boolean flag flips to false.
 */
class MimoChatAdapterResolverTest {

    @Test
    void mimoAdapterUsesPublicOnlyResolverInProdProfile() {
        var adapter = createAdapter(true);
        assertTrue(
            adapter.isPublicOnlyResolverActive(),
            "Production MiMo dispatch MUST use PublicOnlyAddressResolverGroup (M18 DNS-rebinding contract)"
        );
    }

    @Test
    void mimoAdapterDoesNotUsePublicOnlyResolverInNonProdProfile() {
        var adapter = createAdapter(false);
        assertFalse(
            adapter.isPublicOnlyResolverActive(),
            "Non-production MiMo dispatch MUST NOT use PublicOnlyAddressResolverGroup (allows local mock validation)"
        );
    }

    @Test
    void mimoAdapterReportsCorrectCode() {
        var adapter = createAdapter(true);
        assertEquals("MIMO", adapter.adapterCode());
    }

    private MimoChatAdapter createAdapter(boolean prodProfile) {
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(5000);
        properties.setHeaderTimeoutMs(60000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        if (prodProfile) {
            env.addActiveProfile("prod");
        }

        return new MimoChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);
    }
}