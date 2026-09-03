package com.aicostops.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Fail-closed proof for the enabled limiter: an empty script completion or a
 * malformed script result surfaces 503
 * {@code GATEWAY_DEPENDENCY_UNAVAILABLE} before any Provider I/O, never an
 * empty success and never fail-open.
 */
class RedisTokenBucketRateLimiterFailClosedTest {

    @Test
    void emptyScriptCompletionFailsClosedWithDependencyUnavailable() {
        // A name-dispatched default answer avoids the overloaded
        // execute(...) signatures at compile time.
        Answer<Object> emptyExecute = invocation -> "execute".equals(invocation.getMethod().getName())
                ? Flux.empty()
                : invocation.callRealMethod();
        var redis = mock(ReactiveStringRedisTemplate.class, emptyExecute);
        var limiter = new RedisTokenBucketRateLimiter(redis, enabledProperties(), Clock.systemUTC());

        StepVerifier.create(limiter.tryAcquire(7L))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(GatewayErrorException.class);
                    assertThat(((GatewayErrorException) ex).code())
                            .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE);
                })
                .verify(java.time.Duration.ofSeconds(10));
    }

    @Test
    void malformedScriptResultFailsClosedWithDependencyUnavailable() {
        Answer<Object> malformedExecute = invocation -> "execute".equals(invocation.getMethod().getName())
                ? Flux.just(List.of(1L))
                : invocation.callRealMethod();
        var redis = mock(ReactiveStringRedisTemplate.class, malformedExecute);
        var limiter = new RedisTokenBucketRateLimiter(redis, enabledProperties(), Clock.systemUTC());

        StepVerifier.create(limiter.tryAcquire(7L))
                .expectErrorSatisfies(ex -> {
                    assertThat(ex).isInstanceOf(GatewayErrorException.class);
                    assertThat(((GatewayErrorException) ex).code())
                            .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE);
                })
                .verify(java.time.Duration.ofSeconds(10));
    }

    private static GatewayProperties enabledProperties() {
        var properties = new GatewayProperties();
        properties.setRateLimitEnabled(true);
        properties.setRateLimitCapacity(2);
        properties.setRateLimitRefillPerSecond(1);
        return properties;
    }
}
