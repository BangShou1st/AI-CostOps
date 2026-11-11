package com.aicostops.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * A connected-but-silent Redis must not hold the data plane for the Lettuce
 * default command timeout. The limiter must fail closed with a bounded delay,
 * before any Provider I/O.
 */
class RedisTokenBucketRateLimiterTimeoutTest {

    @Test
    void silentRedisFailsClosedWithinTheConfiguredCommandTimeout() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                try (var accepted = server.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignored) {
                    // Test teardown closes the server/socket.
                }
            });
            var standalone = new RedisStandaloneConfiguration(
                    "127.0.0.1", server.getLocalPort());
            var clientConfig = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(300))
                    .build();
            var factory = new LettuceConnectionFactory(standalone, clientConfig);
            try {
                factory.afterPropertiesSet();
                var properties = new GatewayProperties();
                properties.setRateLimitEnabled(true);
                properties.setRateLimitCapacity(10);
                properties.setRateLimitRefillPerSecond(1);
                var limiter = new RedisTokenBucketRateLimiter(
                        new ReactiveStringRedisTemplate(factory), properties, Clock.systemUTC());

                var started = System.nanoTime();
                assertThatThrownBy(() -> limiter.tryAcquire(1).block(Duration.ofSeconds(2)))
                        .isInstanceOf(GatewayErrorException.class)
                        .satisfies(error -> assertThat(((GatewayErrorException) error).code())
                                .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));
                var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
                assertThat(elapsedMillis)
                        .as("fail-closed latency stays bounded by the command timeout")
                        .isLessThan(2_000L);
            } finally {
                factory.destroy();
                executor.shutdownNow();
            }
        }
    }
}
