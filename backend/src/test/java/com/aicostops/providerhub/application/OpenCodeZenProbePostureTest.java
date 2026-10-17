package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sharedlibs.opencodezen.OpenCodeZenProtocolPolicy;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * OpenCode Zen CLI-posture probe contract (2026-10-17).
 *
 * <p>The Zen free tier answers only the required streaming CLI envelope, so the built-in template is
 * probed through one bounded streaming exchange. This locks the outbound envelope and the capability
 * classification against SSE bodies shaped like the real upstream response captured from
 * {@code https://opencode.ai/inference/v1/chat/completions} (a legal delta chunk, a terminal
 * {@code [DONE]} marker, and a final {@code choices:[]} frame carrying {@code usage}).
 */
class OpenCodeZenProbePostureTest {

    private static final String CONTENT_DELTA =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"big-pickle\",\"choices\":[{\"index\":0,\"finish_reason\":null,"
            + "\"delta\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"}}]}\n\n";
    private static final String PLAIN_WORD_DELTA =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"big-pickle\",\"choices\":[{\"index\":0,\"finish_reason\":null,"
            + "\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}\n\n";
    private static final String STOP_CHUNK =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"big-pickle\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"delta\":{\"role\":null,\"content\":null}}]}\n\n";
    private static final String USAGE_CHUNK =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
            + "\"model\":\"big-pickle\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
            + "\"completion_tokens\":5,\"total_tokens\":15}}\n\n";
    private static final String DONE = "data: [DONE]\n\n";

    private final ModelProbeService service = new ModelProbeService(
            null, null, null, null, null, null, null,
            new tools.jackson.databind.ObjectMapper(), null, null, new OpenCodeModelManifest());

    @Test
    void probeBodyCarriesTheRequiredStreamingCliEnvelope() {
        var body = service.openCodeZenProbeBody("big-pickle");
        assertTrue(body.contains("\"stream\":true"), body);
        assertFalse(body.contains("\"stream\":false"), body);
        assertTrue(body.contains("\"include_usage\":true"), body);
        assertTrue(body.contains("\"model\":\"big-pickle\""), body);
        for (var tool : OpenCodeZenProtocolPolicy.reservedToolNames()) {
            assertTrue(body.contains("\"name\":\"" + tool + "\""),
                    () -> "Required reserved tool missing from the probe body: " + tool);
        }
    }

    @Test
    void realUpstreamStreamShapeVerifiesChatStreamingAndUsage() {
        var capabilities = new LinkedHashMap<String, String>();
        var outcome = service.classifyOpenCodeZenStream(
                CONTENT_DELTA + STOP_CHUNK + USAGE_CHUNK + DONE, capabilities);
        assertTrue(outcome.pass());
        assertNull(outcome.errorCode());
        assertEquals("VERIFIED", capabilities.get("CHAT_COMPLETIONS"));
        assertEquals("VERIFIED", capabilities.get("SSE_STREAMING"));
        assertEquals("VERIFIED", capabilities.get("USAGE"));
        assertEquals("VERIFIED", capabilities.get("STRUCTURED_JSON"));
    }

    @Test
    void usageFrameWithEmptyChoicesIsNeverALegalCompletionChunk() {
        // A usage frame carries choices:[]; it can prove USAGE but never CHAT_COMPLETIONS.
        var capabilities = new LinkedHashMap<String, String>();
        var outcome = service.classifyOpenCodeZenStream(USAGE_CHUNK + DONE, capabilities);
        assertFalse(outcome.pass());
        assertEquals("PROTOCOL_UNSUPPORTED", outcome.errorCode());
        assertEquals("UNSUPPORTED", capabilities.get("CHAT_COMPLETIONS"));
        assertEquals("UNSUPPORTED", capabilities.get("SSE_STREAMING"));
        assertEquals("VERIFIED", capabilities.get("USAGE"));
    }

    @Test
    void streamWithoutTheTerminalMarkerIsNeverVerified() {
        var capabilities = new LinkedHashMap<String, String>();
        var outcome = service.classifyOpenCodeZenStream(
                CONTENT_DELTA + STOP_CHUNK, capabilities);
        assertFalse(outcome.pass());
        assertEquals("PROTOCOL_UNSUPPORTED", outcome.errorCode());
        assertEquals("UNSUPPORTED", capabilities.get("CHAT_COMPLETIONS"));
        assertEquals("UNSUPPORTED", capabilities.get("SSE_STREAMING"));
    }

    @Test
    void nonJsonContentLeavesStructuredJsonUnsupported() {
        var capabilities = new LinkedHashMap<String, String>();
        var outcome = service.classifyOpenCodeZenStream(
                PLAIN_WORD_DELTA + STOP_CHUNK + USAGE_CHUNK + DONE, capabilities);
        assertTrue(outcome.pass());
        assertEquals("VERIFIED", capabilities.get("CHAT_COMPLETIONS"));
        assertEquals("UNSUPPORTED", capabilities.get("STRUCTURED_JSON"));
    }
}
