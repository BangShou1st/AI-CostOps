package com.aicostops.gateway.quota;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Atomic daily quota backed by {@code redis/gateway-quota.lua}. The Redis key
 * is {@code aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}}; raw API
 * keys never appear in Redis keys. When the quota is enabled and Redis is
 * unavailable this fails closed with 503
 * {@code GATEWAY_DEPENDENCY_UNAVAILABLE} before any Provider I/O: it never
 * fails open.
 */
@Component
public class RedisDailyQuotaLimiter implements GatewayQuotaLimiter {

    private static final DateTimeFormatter UTC_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long MAX_TTL_SECONDS = 2L * 24 * 60 * 60;

    private final ReactiveStringRedisTemplate redis;
    private final DefaultRedisScript<List<Long>> script;
    private final GatewayProperties properties;
    private final Clock clock;

    public RedisDailyQuotaLimiter(
            ReactiveStringRedisTemplate redis,
            GatewayProperties properties,
            Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("redis/gateway-quota.lua"));
        this.script.setResultType((Class<List<Long>>) (Class<?>) List.class);
    }

    @Override
    public Mono<QuotaResult> tryAcquire(long credentialId) {
        if (!properties.isQuotaEnabled()) {
            return Mono.just(QuotaResult.allowed(0L));
        }
        var day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(UTC_DAY);
        var key = "aicostops:v2:gateway:quota:" + credentialId + ":" + day;
        var unavailable = new GatewayErrorException(
                GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                "The quota limiter is unavailable");
        return redis.execute(script,
                        List.of(key),
                        String.valueOf(properties.getQuotaRequestsPerDay()),
                        String.valueOf(secondsUntilUtcMidnight()))
                .next()
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
                                "The quota limiter is unavailable"));
    }

    private long secondsUntilUtcMidnight() {
        var now = clock.instant();
        var zone = ZoneOffset.UTC;
        var tomorrow = LocalDate.now(clock.withZone(zone)).plusDays(1).atStartOfDay(zone).toInstant();
        var ttl = tomorrow.getEpochSecond() - now.getEpochSecond();
        return Math.min(Math.max(ttl, 1L), MAX_TTL_SECONDS);
    }

    private static QuotaResult toResult(List<Long> values) {
        if (values == null || values.size() < 2) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "The quota limiter is unavailable");
        }
        long allowed = values.get(0) == null ? 0L : values.get(0);
        long used = values.get(1) == null ? 0L : values.get(1);
        return allowed == 1L ? QuotaResult.allowed(used) : QuotaResult.rejected(used);
    }
}
