package com.aicostops.providerhub.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sharedlibs.opencodezen.autoconfigure.OpenCodeZenRequestFactory;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenCodeZenTransportHeadersTest {
    private final OpenCodeZenTransportHeaders headers = new OpenCodeZenTransportHeaders(
            new OpenCodeZenRequestFactory(ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT));

    @Test
    void catalogAndProbeKeepOnePinnedCliIdentityAndPublicCredential() {
        var catalog = headers.catalog(URI.create("https://opencode.ai/inference/v1/models"),
                null, "application/json");
        var probe = headers.model(URI.create("https://opencode.ai/inference/v1/chat/completions"),
                true, "stored-key-must-not-be-used", "text/event-stream");
        for (var request : java.util.List.of(catalog, probe)) {
            var normalized = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            normalized.putAll(request);
            assertThat(normalized).hasSize(request.size());
            assertThat(normalized).containsAllEntriesOf(Map.of(
                    "accept", "*/*", "authorization", "Bearer public",
                    "user-agent", ProviderTemplateRegistry.OPENCODE_ZEN_USER_AGENT,
                    "x-opencode-client", "cli"));
            assertThat(normalized.get("x-opencode-project"))
                    .matches("[0-9a-f]{40}");
            assertThat(normalized.get("x-opencode-session"))
                    .matches("ses_[0-9a-f]{12}[0-9A-Za-z]{14}")
                    .isEqualTo(normalized.get("x-opencode-session-id"));
            assertThat(normalized).doesNotContainKeys(
                    "x-opencode-request", "x-session-affinity", "x-session-id");
        }
    }
}
