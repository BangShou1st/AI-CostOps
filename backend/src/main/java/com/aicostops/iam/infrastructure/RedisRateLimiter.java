package com.aicostops.iam.infrastructure;

import com.aicostops.iam.domain.TokenDigest;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;

public class RedisRateLimiter {

    private static final String PREFIX = "aicostops:v1:ratelimit:login:";
    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>();

    static {
        INCREMENT_WITH_EXPIRY.setLocation(new ClassPathResource("redis/login-rate-limit.lua"));
        INCREMENT_WITH_EXPIRY.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int ipLimit;
    private final int accountLimit;
    private final Duration window;

    public RedisRateLimiter(
            StringRedisTemplate redis,
            Clock clock,
            int ipLimit,
            int accountLimit,
            Duration window) {
        this.redis = redis;
        this.clock = clock;
        this.ipLimit = ipLimit;
        this.accountLimit = accountLimit;
        this.window = window;
    }

    public RateLimitDecision checkLogin(String remoteIp, String normalizedEmail) {
        var windowSeconds = Math.max(1L, window.toSeconds());
        var epochSeconds = clock.instant().getEpochSecond();
        var windowId = epochSeconds / windowSeconds;
        var remainingMillis = Math.max(1L, windowSeconds - (epochSeconds % windowSeconds)) * 1000L;
        var ipKey = PREFIX + "ip:" + TokenDigest.sha256(remoteIp) + ":" + windowId;
        var accountKey = PREFIX + "account:" + TokenDigest.sha256(normalizedEmail) + ":" + windowId;
        try {
            var ipCount = incrementWithExpiry(ipKey, remainingMillis);
            var accountCount = incrementWithExpiry(accountKey, remainingMillis);
            if (ipCount > ipLimit || accountCount > accountLimit) {
                return RateLimitDecision.deny(Math.max(1, windowSeconds - (epochSeconds % windowSeconds)));
            }
            return RateLimitDecision.allow();
        } catch (DataAccessException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH,
                    "Authentication runtime unavailable",
                    "Authentication is temporarily unavailable.");
        }
    }

    private long incrementWithExpiry(String key, long remainingMillis) {
        var count = redis.execute(INCREMENT_WITH_EXPIRY, List.of(key), Long.toString(remainingMillis));
        return count == null ? Long.MAX_VALUE : count;
    }
}
