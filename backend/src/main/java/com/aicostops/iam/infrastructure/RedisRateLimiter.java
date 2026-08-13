package com.aicostops.iam.infrastructure;

import com.aicostops.iam.domain.TokenDigest;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

public class RedisRateLimiter {

    private static final String PREFIX = "aicostops:v1:ratelimit:login:";

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
        var windowSeconds = window.toSeconds();
        var epochSeconds = clock.instant().getEpochSecond();
        var windowId = epochSeconds / windowSeconds;
        var ipKey = PREFIX + "ip:" + TokenDigest.sha256(remoteIp) + ":" + windowId;
        var accountKey = PREFIX + "account:" + TokenDigest.sha256(normalizedEmail) + ":" + windowId;
        try {
            var ipCount = incrementWithExpiry(ipKey);
            var accountCount = incrementWithExpiry(accountKey);
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

    private long incrementWithExpiry(String key) {
        var count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null ? Long.MAX_VALUE : count;
    }
}
