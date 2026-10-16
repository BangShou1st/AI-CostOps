package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeZenModelCatalogParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesExplicitPriceProtocolAndRetirementMetadata() throws Exception {
        var root = mapper.readTree("""
                {
                  "opencode": {
                    "npm": "@ai-sdk/openai-compatible",
                    "models": {
                      "free-chat": {"cost": {"input": 0, "output": 0}},
                      "paid-free-suffix": {"cost": {"input": 0.1, "output": 0.2}},
                      "free-responses": {"cost": {"input": 0, "output": 0}, "provider": {"npm": "@ai-sdk/openai"}},
                      "retired-free": {"cost": {"input": 0, "output": 0}, "deprecated": true},
                      "unknown-price": {}
                    }
                  }
                }
                """);

        var catalog = OpenCodeZenModelCatalogParser.parse(root).orElseThrow();

        assertThat(catalog.get("free-chat").free()).isTrue();
        assertThat(catalog.get("free-chat").protocol()).isEqualTo(ZenModelProtocol.CHAT);
        assertThat(catalog.get("paid-free-suffix").free()).isFalse();
        assertThat(catalog.get("free-responses").free()).isTrue();
        assertThat(catalog.get("free-responses").protocol()).isEqualTo(ZenModelProtocol.RESPONSES);
        assertThat(catalog.get("retired-free").retired()).isTrue();
        assertThat(catalog.get("unknown-price").free()).isFalse();
    }

    /**
     * A {@code -free} suffix is never evidence. Without an available, authoritative catalog no
     * model can be proven free, so every lookup fails closed instead of falling back to the name.
     */
    @Test
    void failsClosedWithoutAnAvailableCatalogAndNeverUsesASuffixFallback() {
        ZenModelMetadata knownFree = new ZenModelMetadata("free", true, false, ZenModelProtocol.CHAT);

        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("free", true, true, knownFree)).isTrue();
        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("unknown-free", true, true, null)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("unknown-free", true, false, null)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("unknown-free", false, false, null)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isChatModelForListing("unknown", true, true, null)).isFalse();
    }

    /**
     * Runtime execution admits only a model that is simultaneously Chat, explicitly free, and not
     * retired. Chat compatibility alone, or a free price alone, is never sufficient.
     */
    @Test
    void verifiedChatModelRequiresChatFreeAndActiveTogether() {
        ZenModelMetadata freeChat = new ZenModelMetadata("free", true, false, ZenModelProtocol.CHAT);
        ZenModelMetadata paidChat = new ZenModelMetadata("paid", false, false, ZenModelProtocol.CHAT);
        ZenModelMetadata freeNonChat = new ZenModelMetadata("resp", true, false, ZenModelProtocol.RESPONSES);
        ZenModelMetadata freeRetired = new ZenModelMetadata("retired", true, true, ZenModelProtocol.CHAT);

        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("free", true, true, freeChat)).isTrue();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("paid", true, true, paidChat)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("resp", true, true, freeNonChat)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("retired", true, true, freeRetired)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("free", true, false, freeChat)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("free", false, false, freeChat)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel(null, true, true, freeChat)).isFalse();
    }
}
