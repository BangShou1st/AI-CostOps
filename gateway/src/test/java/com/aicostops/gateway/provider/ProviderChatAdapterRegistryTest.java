package com.aicostops.gateway.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ProviderChatAdapterRegistryTest {

    @Test
    void duplicateAdapterCodesFailFast() {
        var first = mock(ProviderChatAdapter.class);
        var second = mock(ProviderChatAdapter.class);
        when(first.adapterCode()).thenReturn("OPENAI");
        when(second.adapterCode()).thenReturn("openai");
        assertThatThrownBy(() -> new ProviderChatAdapterRegistry(java.util.List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void contextStringDoesNotExposeCredential() {
        var context = new ProviderCallContext("OPENAI", 1, 2, "model", 3, "USD",
                "https://example.test", "API_KEY", "super-secret".getBytes(), "route-1");
        org.assertj.core.api.Assertions.assertThat(context.toString())
                .doesNotContain("super-secret")
                .contains("OPENAI");
    }
}
