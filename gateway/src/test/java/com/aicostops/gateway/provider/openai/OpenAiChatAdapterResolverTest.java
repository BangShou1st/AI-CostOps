package com.aicostops.gateway.provider.openai;

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
 * M21 post-release SSRF regression test for OpenAI adapter.
 *
 * <p>This test verifies the OpenAI adapter wires PublicOnlyAddressResolverGroup
 * into its HttpClient when the production profile is active, enforcing the
 * frozen M18 DNS-rebinding / SSRF contract.
 *
 * <p>Mutation proof: if the resolver wiring line is removed from
 * OpenAiChatAdapter.buildHttpClient(), this test FAILS because the resolver
 * type assertion no longer matches.
 */
class OpenAiChatAdapterResolverTest {

    @Test
    void openAiAdapterUsesPublicOnlyResolverInProdProfile() {
        var adapter = createAdapter(true);

        HttpClient httpClient = adapter.httpClientForTest();
        AddressResolverGroup<?> resolverGroup = httpClient.configuration().resolverGroup();

        assertNotNull(resolverGroup, "Resolver group must be configured");
        assertTrue(
            resolverGroup instanceof PublicOnlyAddressResolverGroup,
            "Production OpenAI dispatch MUST use PublicOnlyAddressResolverGroup, but was: "
                + resolverGroup.getClass().getName()
        );
    }

    @Test
    void openAiAdapterDoesNotUsePublicOnlyResolverInNonProdProfile() {
        var adapter = createAdapter(false);

        HttpClient httpClient = adapter.httpClientForTest();
        AddressResolverGroup<?> resolverGroup = httpClient.configuration().resolverGroup();

        assertFalse(
            resolverGroup instanceof PublicOnlyAddressResolverGroup,
            "Non-production OpenAI dispatch MUST NOT use PublicOnlyAddressResolverGroup (allows local mock validation)"
        );
    }

    @Test
    void openAiAdapterReportsCorrectCode() {
        var adapter = createAdapter(true);
        assertEquals("OPENAI", adapter.adapterCode());
    }

    private OpenAiChatAdapter createAdapter(boolean prodProfile) {
        var properties = new GatewayProperties();
        properties.setConnectTimeoutMs(5000);
        properties.setHeaderTimeoutMs(60000);
        properties.setMaxInMemoryBytes(16777216);

        MockEnvironment env = new MockEnvironment();
        if (prodProfile) {
            env.addActiveProfile("prod");
        }

        return new OpenAiChatAdapter(WebClient.builder(), new ObjectMapper(), properties, env);
    }
}