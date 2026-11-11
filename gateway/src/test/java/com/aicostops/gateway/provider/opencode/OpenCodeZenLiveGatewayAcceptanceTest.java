package com.aicostops.gateway.provider.opencode;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.routing.OpenCodeFreeOnlyAdmission;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase C live verification test for Gateway OpenCodeZenChatAdapter with OpenCode Zen upstream via Bun relay.
 */
@EnabledIfSystemProperty(named = "zen.live", matches = "true")
class OpenCodeZenLiveGatewayAcceptanceTest {

    private static final String MODEL = "deepseek-v4.1-flash-free";

    private ProviderCallContext sampleContext() {
        return new ProviderCallContext(
                "OPENCODE_ZEN", 1L, 2L, MODEL, 3L, "USD",
                "https://opencode.ai/inference/v1", "NONE", new byte[0],
                "route-decision-live-001", 7L,
                "/chat/completions", "OPENAI_CHAT_COMPLETIONS", "DIRECT_ONLY",
                OpenCodeZenChatAdapter.SERVER_USER_AGENT);
    }

    private ChatCompletionCommand sampleCommand() {
        return new ChatCompletionCommand(
                MODEL,
                List.of(new ChatCompletionCommand.Message("user", "Reply with exactly: PONG")),
                32, false);
    }

    @Test
    void realGatewayCompleteAggregatesSseAndReceivesPong() {
        var factory = new OpenCodeZenRequestFactory(
                OpenCodeZenChatAdapter.SERVER_USER_AGENT, false, "http://127.0.0.1:18992");
        var adapter = new OpenCodeZenChatAdapter(
                WebClient.builder(), new ObjectMapper(), new GatewayProperties(),
                new DispatchEndpointGuard(), factory, OpenCodeFreeOnlyAdmission.production());

        var completion = adapter.complete(sampleContext(), sampleCommand()).block(Duration.ofSeconds(30));

        System.out.println("LIVE GATEWAY COMPLETION: id=" + completion.upstreamId()
                + ", content=" + completion.choices().get(0).content()
                + ", usage=" + completion.usage());

        assertThat(completion).isNotNull();
        assertThat(completion.choices()).isNotEmpty();
        var content = completion.choices().get(0).content();
        assertThat(content).contains("PONG");
        assertThat(completion.usage()).isNotNull();
        assertThat(completion.usage().promptTokens()).isGreaterThan(0);
        assertThat(completion.usage().completionTokens()).isGreaterThan(0);
        assertThat(completion.usage().totalTokens()).isGreaterThan(0);
    }
}
