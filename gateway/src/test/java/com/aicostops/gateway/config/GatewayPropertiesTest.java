package com.aicostops.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** M11 resource bounds are mandatory: non-positive/unbounded limits reject startup. */
class GatewayPropertiesTest {

    @Test
    void factoryDefaultsPassValidation() {
        assertThatCode(new GatewayProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositiveDbThreads() {
        assertRejects(p -> p.setDbThreads(0), "dbThreads");
        assertRejects(p -> p.setDbThreads(-4), "dbThreads");
    }

    @Test
    void rejectsNonPositiveDbQueueCapacity() {
        assertRejects(p -> p.setDbQueueCapacity(0), "dbQueueCapacity");
    }

    @Test
    void rejectsNonPositiveDbPoolMax() {
        assertRejects(p -> p.setDbPoolMax(0), "dbPoolMax");
    }

    @Test
    void rejectsNonPositiveMaxActiveStreams() {
        assertRejects(p -> p.setMaxActiveStreams(0), "maxActiveStreams");
    }

    @Test
    void rejectsNonPositiveMaxRequestBytes() {
        assertRejects(p -> p.setMaxRequestBytes(0), "maxRequestBytes");
    }

    @Test
    void rejectsNonPositiveMaxHeaderBytes() {
        assertRejects(p -> p.setMaxHeaderBytes(0), "maxHeaderBytes");
    }

    @Test
    void rejectsNonPositiveMaxInMemoryBytes() {
        assertRejects(p -> p.setMaxInMemoryBytes(0), "maxInMemoryBytes");
    }

    @Test
    void rejectsNonPositiveConnectTimeoutMs() {
        assertRejects(p -> p.setConnectTimeoutMs(0), "connectTimeoutMs");
    }

    @Test
    void rejectsNonPositiveHeaderTimeoutMs() {
        assertRejects(p -> p.setHeaderTimeoutMs(0), "headerTimeoutMs");
    }

    @Test
    void rejectsNonPositiveStreamIdleTimeoutMs() {
        assertRejects(p -> p.setStreamIdleTimeoutMs(0), "streamIdleTimeoutMs");
    }

    @Test
    void rejectsNonPositiveHardTimeoutMs() {
        assertRejects(p -> p.setHardTimeoutMs(-1), "hardTimeoutMs");
    }

    @Test
    void rejectsZeroRateLimitCapacityWhenEnabled() {
        assertRejects(p -> p.setRateLimitCapacity(0), "rateLimitCapacity");
    }

    @Test
    void rejectsNonPositiveRefillRateWhenEnabled() {
        assertRejects(p -> p.setRateLimitRefillPerSecond(0.0), "rateLimitRefillPerSecond");
    }

    @Test
    void rateLimitDisabledDoesNotRequireCapacityOrRefill() {
        var properties = new GatewayProperties();
        properties.setRateLimitEnabled(false);
        properties.setRateLimitCapacity(0);
        properties.setRateLimitRefillPerSecond(0.0);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonPositivePort() {
        assertRejects(p -> p.setPort(0), "port");
    }

    private static void assertRejects(java.util.function.Consumer<GatewayProperties> mutate, String field) {
        var properties = new GatewayProperties();
        mutate.accept(properties);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(field);
    }
}