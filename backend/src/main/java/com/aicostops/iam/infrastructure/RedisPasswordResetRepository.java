package com.aicostops.iam.infrastructure;

import com.aicostops.iam.domain.TokenDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisPasswordResetRepository {
    private static final String PREFIX = "aicostops:v1:auth:reset:";
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>();
    static {
        CONSUME.setLocation(new ClassPathResource("redis/password-reset-consume.lua"));
        CONSUME.setResultType(String.class);
    }
    private final StringRedisTemplate redis;
    private final SecureRandom random;
    private final Duration lifetime;

    public RedisPasswordResetRepository(StringRedisTemplate redis, SecureRandom random, Duration lifetime) {
        this.redis = redis; this.random = random; this.lifetime = lifetime;
    }

    public String create(long userId) {
        var tokenId = randomPart(); var secret = randomPart();
        var key = PREFIX + tokenId;
        redis.opsForHash().putAll(key, Map.of("user_id", Long.toString(userId),
                "token_hash", TokenDigest.sha256(secret)));
        redis.expire(key, lifetime);
        return tokenId + "." + secret;
    }

    public Long consume(String rawToken) {
        var separator = rawToken == null ? -1 : rawToken.indexOf('.');
        if (separator < 1 || separator == rawToken.length() - 1) return null;
        var result = redis.execute(CONSUME, List.of(PREFIX + rawToken.substring(0, separator)),
                TokenDigest.sha256(rawToken.substring(separator + 1)));
        return result == null ? null : Long.parseLong(result);
    }

    private String randomPart() {
        var bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
