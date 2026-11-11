package com.aicostops.providerhub.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.providerhub.domain.ProviderConnection;
import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase B live verification test for ModelProbeService with OpenCode Zen upstream via Bun relay.
 */
@EnabledIfSystemProperty(named = "zen.live", matches = "true")
class OpenCodeZenLiveProbeAcceptanceTest {

    private static final String MODEL = "deepseek-v4.1-flash-free";

    private ProviderConnection sampleProfile() {
        return new ProviderConnection(
                1L, 100L, 200L, 1, "BUILTIN", "OPENCODE_ZEN", "OPENAI_CHAT_COMPLETIONS",
                "https://opencode.ai/inference/v1", "/chat/completions",
                "/models", "BEARER", null,
                "DIRECT_ONLY", ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT,
                10000, 30000, "ACTIVE", 1L, Instant.now(), Instant.now(), null);
    }

    @Test
    void realModelProbeReachesUpstreamAndReceivesHttp200AndDone() throws Exception {
        var factory = new OpenCodeZenRequestFactory(
                ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT, false, "http://127.0.0.1:18992");
        var headersAdapter = new OpenCodeZenTransportHeaders(factory);

        var service = new ModelProbeService(
                null, null, null, null,
                new CustomEndpointValidator(),
                new ProviderControlPlaneTransport(),
                null, new ObjectMapper(), java.time.Clock.systemUTC(),
                null, new OpenCodeModelManifest(),
                new KiloGatewayModelManifest(),
                headersAdapter);

        var capabilities = new LinkedHashMap<String, String>();
        try {
            var outcome = service.probeOpenCodeZen(sampleProfile(), MODEL, null, null, capabilities);
            System.out.println("probeOpenCodeZen OUTCOME: " + outcome);
        } catch (Exception ex) {
            System.out.println("probeOpenCodeZen EXCEPTION: " + ex.getClass().getName() + ": " + ex.getMessage());
            ex.printStackTrace();
        }

        var outcome = service.execute(sampleProfile(), MODEL);
        System.out.println("LIVE PROBE RESULT: pass=" + outcome.pass()
                + ", error=" + outcome.errorCode()
                + ", capabilities=" + outcome.capabilities());

        assertThat(outcome.pass())
                .as("Live upstream probe must pass with real HTTP 200")
                .isTrue();
        assertThat(outcome.capabilities().get("CHAT_COMPLETIONS")).isEqualTo("VERIFIED");
        assertThat(outcome.capabilities().get("SSE_STREAMING")).isEqualTo("VERIFIED");
        assertThat(outcome.capabilities().get("USAGE")).isEqualTo("VERIFIED");
    }
}
