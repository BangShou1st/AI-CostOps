package com.aicostops.gateway.ratelimit;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Atomic token-bucket limiter backed by {@code redis/gateway-rate-limit.lua}.
 * The Redis key is {@code aicostops:v2:gateway:ratelimit:{credentialId}}; raw
 * API keys never appear in Redis keys. When the limiter is enabled and Redis
 * is unavailable this fails closed with 503 {@code GATEWAY_DEPENDENCY_UNAVAILABLE}
 * before any Provider I/O: it never fails open.
 */
@Component
public class RedisTokenBucketRateLimiter implements GatewayRateLimiter {

    private final ReactiveStringRedisTemplate redis;
    private final DefaultRedisScript<List<Long>> script;
    private final GatewayProperties properties;
    private final Clock clock;

    public RedisTokenBucketRateLimiter(
            ReactiveStringRedisTemplate redis,
            GatewayProperties properties,
            Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/gateway-rate-limit.lua"));
        this.script.setResultType((Class<List<Long>>) (Class<?>) List.class);
    }

    @Override
    public Mono<RateLimitResult> tryAcquire(long credentialId) {
        if (!properties.isRateLimitEnabled()) {
            return Mono.just(RateLimitResult.allowed(Long.MAX_VALUE));
        }
        var key = "aicostops:v2:gateway:ratelimit:" + credentialId;
        var unavailable = new GatewayErrorException(
                GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                "The rate limiter is unavailable");
        return redis.execute(script,
                        List.of(key),
                        String.valueOf(properties.getRateLimitCapacity()),
                        String.valueOf(properties.getRateLimitRefillPerSecond()),
                        String.valueOf(Instant.now(clock).toEpochMilli()),
                        "1")
                .next()
                // An abnormally empty script completion carries no verdict: fail
                // closed instead of completing empty.
                .switchIfEmpty(Mono.error(unavailable))
                .flatMap(values -> {
                    if (values == null) {
                        return Mono.error(unavailable);
                    }
                    return Mono.just(toResult(values));
                })
                .onErrorMap(ex -> ex instanceof GatewayErrorException gatewayError
                                && gatewayError.code()
                                        == GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE
                        ? gatewayError
                        : new GatewayErrorException(
                                GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                                "The rate limiter is unavailable"));
    }

    private static RateLimitResult toResult(List<Long> values) {
        if (values == null || values.size() < 3) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "The rate limiter is unavailable");
        }
        long allowed = values.get(0) == null ? 0L : values.get(0);
        long remaining = values.get(1) == null ? 0L : values.get(1);
        long retryAfterMillis = values.get(2) == null ? 0L : values.get(2);
        return allowed == 1L
                ? RateLimitResult.allowed(remaining)
                : RateLimitResult.rejected(remaining, retryAfterMillis);
    }
}