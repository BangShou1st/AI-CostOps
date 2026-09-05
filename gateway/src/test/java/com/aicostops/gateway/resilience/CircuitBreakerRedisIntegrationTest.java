package com.aicostops.gateway.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/** Two independent circuit instances share exactly one Redis HALF_OPEN probe. */
class CircuitBreakerRedisIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory factoryOne;
    private static LettuceConnectionFactory factoryTwo;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        factoryOne = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factoryTwo = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factoryOne.afterPropertiesSet();
        factoryTwo.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (factoryOne != null) factoryOne.destroy();
        if (factoryTwo != null) factoryTwo.destroy();
        REDIS.stop();
    }

    @Test
    void onlyOneReplicaOwnsHalfOpenProbe() throws Exception {
        var properties = new GatewayProperties();
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenDurationMs(10);
        properties.setCircuitHalfOpenLeaseMs(5_000);
        var one = new RedisCircuitBreakerService(new ReactiveStringRedisTemplate(factoryOne),
                properties, Clock.systemUTC());
        var two = new RedisCircuitBreakerService(new ReactiveStringRedisTemplate(factoryTwo),
                properties, Clock.systemUTC());
        var key = new RouteCircuitKey(101, 202, 303);

        one.recordFailure(key, ProviderHealthSignal.QUALIFYING_FAILURE).block();
        Thread.sleep(30L);

        var decisions = List.of(one.beforeCall(key).block(), two.beforeCall(key).block());

        assertThat(decisions.stream().filter(CircuitDecision::probeAllowed)).hasSize(1);
        assertThat(decisions.stream().filter(decision -> decision.state() == CircuitState.OPEN)).hasSize(1);
        one.recordSuccess(key).block();
        assertThat(two.beforeCall(key).block().state()).isEqualTo(CircuitState.CLOSED);
    }
}
