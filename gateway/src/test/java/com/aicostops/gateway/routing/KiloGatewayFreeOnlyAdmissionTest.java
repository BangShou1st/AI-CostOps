package com.aicostops.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Kilo Gateway free admission barrier (2026-10-18).
 *
 * <p>The barrier executes the shared library free-model policy for the built-in
 * {@code KILO_GATEWAY} adapter only, and never constrains any other Provider.
 */
class KiloGatewayFreeOnlyAdmissionTest {

    private final KiloGatewayFreeOnlyAdmission admission = new KiloGatewayFreeOnlyAdmission();

    @Test
    void freeIdsAreAdmitted() {
        assertThat(admission.admits("KILO_GATEWAY", "poolside/laguna-s-2.1:free")).isTrue();
        assertThat(admission.admits("KILO_GATEWAY", "kilo-auto/free")).isTrue();
    }

    @Test
    void paidAndUnknownIdsFailClosed() {
        assertThat(admission.admits("KILO_GATEWAY", "deepseek-v4-pro")).isFalse();
        assertThat(admission.admits("KILO_GATEWAY", null)).isFalse();
        assertThat(admission.admits("KILO_GATEWAY", "  ")).isFalse();
    }

    @Test
    void otherAdaptersAreNeverConstrained() {
        assertThat(admission.admits("OPENCODE_ZEN", "deepseek-v4-pro")).isTrue();
        assertThat(admission.admits("OPENAI", "gpt-5")).isTrue();
        assertThat(admission.admits(null, "anything")).isTrue();
    }

    @Test
    void adapterCodeMatchIsCaseInsensitiveAndTrimmed() {
        assertThat(admission.isKiloGateway(" kilo_gateway ")).isTrue();
        assertThat(admission.isKiloGateway("openai")).isFalse();
        assertThat(admission.isKiloGateway(null)).isFalse();
    }
}
