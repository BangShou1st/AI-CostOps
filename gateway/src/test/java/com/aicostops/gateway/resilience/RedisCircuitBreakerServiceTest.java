package com.aicostops.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RedisCircuitBreakerServiceTest {

    @Test
    void fifthFailureOpensAndOneHalfOpenProbeCanRecover() {
        var properties = new GatewayProperties();
        properties.setCircuitFailureThreshold(5);
        properties.setCircuitOpenDurationMs(30_000);
        properties.setCircuitHalfOpenLeaseMs(15_000);
        var clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        var service = new RedisCircuitBreakerService(properties, clock);
        var key = new RouteCircuitKey(1, 2, 3);
        for (var i = 0; i < 4; i++) service.recordFailure(key, ProviderHealthSignal.QUALIFYING_FAILURE).block();
        assertThat(service.beforeCall(key).block().probeAllowed()).isTrue();
        service.recordFailure(key, ProviderHealthSignal.QUALIFYING_FAILURE).block();
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.open());
    }

    @Test
    void clientCancellationDoesNotPoisonCircuit() {
        var properties = new GatewayProperties();
        var service = new RedisCircuitBreakerService(properties,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var key = new RouteCircuitKey(1, 2, 3);
        service.recordFailure(key, ProviderHealthSignal.CLIENT_CANCELLATION).block();
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.closed());
    }
}
