package com.aicostops.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Process-local active-stream bound: non-blocking acquire, hard ceiling from
 * configuration, and release restoring capacity (the controller always
 * releases on complete/error/cancel).
 */
class GatewayResourceLimiterTest {

    private static GatewayResourceLimiter limiter(int maxActiveStreams) {
        var properties = new GatewayProperties();
        properties.setMaxActiveStreams(maxActiveStreams);
        return new GatewayResourceLimiter(properties);
    }

    @Test
    void acquiresUpToConfiguredBoundAndRejectsBeyond() {
        var limiter = limiter(3);

        assertThat(limiter.tryAcquireStreamPermit()).isTrue();
        assertThat(limiter.tryAcquireStreamPermit()).isTrue();
        assertThat(limiter.tryAcquireStreamPermit()).isTrue();
        assertThat(limiter.tryAcquireStreamPermit()).isFalse();
        assertThat(limiter.availableStreamPermits()).isZero();
    }

    @Test
    void releaseRestoresCapacityAfterBoundExhausted() {
        var limiter = limiter(2);
        assertThat(limiter.tryAcquireStreamPermit()).isTrue();
        assertThat(limiter.tryAcquireStreamPermit()).isTrue();
        assertThat(limiter.tryAcquireStreamPermit()).isFalse();

        limiter.releaseStreamPermit();

        assertThat(limiter.tryAcquireStreamPermit()).isTrue();
        assertThat(limiter.availableStreamPermits()).isZero();
    }

    @Test
    void multipleReleaseCyclesRemainBounded() {
        var limiter = limiter(1);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquireStreamPermit()).isTrue();
            assertThat(limiter.tryAcquireStreamPermit()).isFalse();
            limiter.releaseStreamPermit();
        }
        assertThat(limiter.availableStreamPermits()).isEqualTo(1);
    }

    @Test
    void defaultConfiguredBoundIsPositiveAndObservable() {
        var properties = new GatewayProperties();
        var limiter = new GatewayResourceLimiter(properties);

        assertThat(properties.getMaxActiveStreams()).isGreaterThan(0);
        assertThat(limiter.availableStreamPermits())
                .isEqualTo(properties.getMaxActiveStreams());
    }
}