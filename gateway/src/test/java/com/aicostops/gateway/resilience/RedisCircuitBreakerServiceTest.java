package com.aicostops.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RedisCircuitBreakerServiceTest {

    @Test
    void fifthFailureOpensLocalCircuit() {
        var properties = new GatewayProperties();
        properties.setCircuitFailureThreshold(5);
        properties.setCircuitOpenDurationMs(30_000);
        var clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        var service = new RedisCircuitBreakerService(properties, clock);
        var key = new RouteCircuitKey(1, 2, 3);
        for (var i = 0; i < 4; i++) service.recordFailure(key, ProviderHealthSignal.QUALIFYING_FAILURE).block();
        assertThat(service.beforeCall(key).block().probeAllowed()).isTrue();
        service.recordFailure(key, ProviderHealthSignal.QUALIFYING_FAILURE).block();
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.open());
    }

    @Test
    void canceledLocalHalfOpenProbeCanBeRetriedAfterItsLeaseExpires() {
        var properties = new GatewayProperties();
        var clock = mock(Clock.class);
        var redis = mock(ReactiveStringRedisTemplate.class);
        var key = new RouteCircuitKey(1, 2, 3);
        ReactiveValueOperations<String, String> unavailableValues = mock();
        when(redis.opsForValue()).thenReturn(unavailableValues);
        when(unavailableValues.get(key.redisKey()))
                .thenReturn(Mono.error(new IllegalStateException("Redis unavailable")));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenReturn(Flux.error(new IllegalStateException("Redis unavailable")));
        var service = new RedisCircuitBreakerService(redis, properties, clock);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        service.recordFailure(key, ProviderHealthSignal.ROUTE_CONFIGURATION_FAILURE).block();
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.open());

        var firstProbeAt = Instant.EPOCH.plusMillis(properties.getCircuitOpenDurationMs());
        when(clock.instant()).thenReturn(firstProbeAt);
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.probe());
        service.recordFailure(key, ProviderHealthSignal.CLIENT_CANCELLATION).block();
        when(clock.instant()).thenReturn(firstProbeAt.plusMillis(properties.getCircuitHalfOpenLeaseMs() - 1));
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.open());
        when(clock.instant()).thenReturn(firstProbeAt.plusMillis(properties.getCircuitHalfOpenLeaseMs()));
        assertThat(service.beforeCall(key).block()).isEqualTo(CircuitDecision.probe());
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
