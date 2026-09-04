package com.aicostops.gateway.resilience;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Redis-coordinated route circuit with bounded local degradation. */
@Component
public class RedisCircuitBreakerService implements CircuitBreakerService {

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<RouteCircuitKey, LocalState> local = new ConcurrentHashMap<>();

    @Autowired
    public RedisCircuitBreakerService(ReactiveStringRedisTemplate redis, GatewayProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public RedisCircuitBreakerService(GatewayProperties properties, Clock clock) {
        this.redis = null;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<CircuitDecision> beforeCall(RouteCircuitKey key) {
        if (redis == null) return Mono.fromSupplier(() -> localBeforeCall(key));
        var redisKey = key.redisKey();
        return redis.opsForValue().get(redisKey)
                .defaultIfEmpty("CLOSED|0|0")
                .flatMap(value -> redisBeforeCall(key, redisKey, value))
                .onErrorResume(ignored -> Mono.fromSupplier(() -> localBeforeCall(key)));
    }

    @Override
    public Mono<Void> recordSuccess(RouteCircuitKey key) {
        if (redis == null) {
            local.remove(key);
            return Mono.empty();
        }
        return redis.opsForValue().set(key.redisKey(), "CLOSED|0|0")
                .then(redis.delete(key.redisKey() + ":probe"))
                .then()
                .onErrorResume(ignored -> {
                    local.remove(key);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> recordFailure(RouteCircuitKey key, ProviderHealthSignal signal) {
        if (signal == null || signal == ProviderHealthSignal.NONE
                || signal == ProviderHealthSignal.CLIENT_CANCELLATION) return Mono.empty();
        if (redis == null) {
            localRecordFailure(key);
            return Mono.empty();
        }
        var redisKey = key.redisKey();
        return redis.opsForValue().get(redisKey).defaultIfEmpty("CLOSED|0|0")
                .flatMap(value -> {
                    var parsed = parse(value);
                    var now = nowMillis();
                    if (parsed.state == CircuitState.HALF_OPEN
                            || parsed.failures + 1 >= properties.getCircuitFailureThreshold()) {
                        return redis.opsForValue().set(redisKey, "OPEN|0|" + now)
                                .then(redis.delete(redisKey + ":probe"));
                    }
                    return redis.opsForValue().set(redisKey, "CLOSED|" + (parsed.failures + 1) + "|0");
                }).then().onErrorResume(ignored -> {
                    localRecordFailure(key);
                    return Mono.empty();
                });
    }

    private Mono<CircuitDecision> redisBeforeCall(RouteCircuitKey key, String redisKey, String value) {
        var parsed = parse(value);
        if (parsed.state == CircuitState.CLOSED) return Mono.just(CircuitDecision.closed());
        if (parsed.state == CircuitState.HALF_OPEN) return acquireProbe(redisKey);
        if (nowMillis() - parsed.openedAt < properties.getCircuitOpenDurationMs()) return Mono.just(CircuitDecision.open());
        return acquireProbe(redisKey).flatMap(decision -> {
            if (!decision.probeAllowed()) return Mono.just(decision);
            return redis.opsForValue().set(redisKey, "HALF_OPEN|0|" + parsed.openedAt).thenReturn(decision);
        });
    }

    private Mono<CircuitDecision> acquireProbe(String redisKey) {
        return redis.opsForValue().setIfAbsent(redisKey + ":probe", "1",
                        Duration.ofMillis(properties.getCircuitHalfOpenLeaseMs()))
                .map(allowed -> Boolean.TRUE.equals(allowed) ? CircuitDecision.probe() : CircuitDecision.open());
    }

    private CircuitDecision localBeforeCall(RouteCircuitKey key) {
        var state = local.computeIfAbsent(key, ignored -> new LocalState(CircuitState.CLOSED, 0, 0));
        synchronized (state) {
            if (state.state == CircuitState.CLOSED) return CircuitDecision.closed();
            if (state.state == CircuitState.HALF_OPEN) return state.probeInUse
                    ? CircuitDecision.open() : markProbe(state);
            if (nowMillis() - state.openedAt < properties.getCircuitOpenDurationMs()) return CircuitDecision.open();
            state.state = CircuitState.HALF_OPEN;
            return markProbe(state);
        }
    }

    private CircuitDecision markProbe(LocalState state) {
        state.probeInUse = true;
        return CircuitDecision.probe();
    }

    private void localRecordFailure(RouteCircuitKey key) {
        var state = local.computeIfAbsent(key, ignored -> new LocalState(CircuitState.CLOSED, 0, 0));
        synchronized (state) {
            state.probeInUse = false;
            if (state.state == CircuitState.HALF_OPEN || ++state.failures >= properties.getCircuitFailureThreshold()) {
                state.state = CircuitState.OPEN;
                state.openedAt = nowMillis();
            }
        }
    }

    private long nowMillis() { return Instant.now(clock).toEpochMilli(); }

    private static Parsed parse(String value) {
        var parts = value.split("\\|");
        try {
            return new Parsed(CircuitState.valueOf(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
                    parts.length > 2 ? Long.parseLong(parts[2]) : 0);
        } catch (RuntimeException ignored) {
            return new Parsed(CircuitState.CLOSED, 0, 0);
        }
    }

    private record Parsed(CircuitState state, int failures, long openedAt) { }

    private static final class LocalState {
        private CircuitState state;
        private int failures;
        private long openedAt;
        private boolean probeInUse;

        private LocalState(CircuitState state, int failures, long openedAt) {
            this.state = state;
            this.failures = failures;
            this.openedAt = openedAt;
        }
    }
}
