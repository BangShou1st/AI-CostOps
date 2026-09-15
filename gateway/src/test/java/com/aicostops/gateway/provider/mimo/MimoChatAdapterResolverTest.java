package com.aicostops.gateway.provider.mimo;

import static org.junit.jupiter.api.Assertions.*;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.PublicOnlyAddressResolverGroup;
import io.netty.resolver.AddressResolverGroup;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;

/**
 * M21 post-release SSRF regression test for MiMo adapter.
 *
 * <p>This test verifies the MiMo adapter wires PublicOnlyAddressResolverGroup
 * into its HttpClient when the production profile is active, enforcing the
 * frozen M18 DNS-rebinding / SSRF contract.
 *
 * <p>Mutation proof: if the resolver wiring line is removed from
 * MimoChatAdapter.buildHttpClient(), this test FAILS because the resolver
 * type assertion no longer matches.
 */
class MimoChatAdapterResolverTest {

    @Test
    void mimoAdapterUsesPublicOnlyResolverInProdProfile() {
        var adapter = createAdapter(true);

        // Extract the actual resolver from the HttpClient
        HttpClient httpClient = adapter.httpClientForTest();
        AddressResolverGroup<?> resolverGroup = httpClient.configuration().resolverGroup();

        assertNotNull(resolverGroup, "Resolver group must be configured");
        assertTrue(
            resolverGroup instanceof PublicOnlyAddressResolverGroup,
            "Production MiMo dispatch MUST use PublicOnlyAddressResolverGroup, but was: "
                + resolverGroup.getClass().getName()
        );
    }

    @Test
    void mimoAdapterDoesNotUsePublicOnlyResolverInNonProdProfile() {
        var adapter = createAdapter(false);

        HttpClient httpClient = adapter.httpClientForTest();
        AddressResolverGroup<?> resolverGroup = httpClient.configuration().resolverGroup();

        assertFalse(
            resolverGroup instanceof PublicOnlyAddressResolverGroup,
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