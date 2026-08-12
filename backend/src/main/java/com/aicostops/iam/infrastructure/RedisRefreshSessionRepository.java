package com.aicostops.iam.infrastructure;

import com.aicostops.iam.domain.TokenDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisRefreshSessionRepository {

    private static final String PREFIX = "aicostops:v1:auth:refresh:";
    private static final DefaultRedisScript<String> ROTATION_SCRIPT = new DefaultRedisScript<>();

    static {
        ROTATION_SCRIPT.setLocation(new ClassPathResource("redis/refresh-rotate.lua"));
        ROTATION_SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Duration sessionLifetime;
    private final Duration raceWindow;

    public RedisRefreshSessionRepository(
            StringRedisTemplate redis,
            Clock clock,
            SecureRandom secureRandom,
            Duration sessionLifetime,
            Duration raceWindow) {
        this.redis = redis;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.sessionLifetime = sessionLifetime;
        this.raceWindow = raceWindow;
    }

    public RefreshCredential create(long userId, long organizationMemberId, long securityVersion, String deviceLabel) {
        var sessionId = randomPart();
        var secret = randomPart();
        var now = clock.millis();
        var fields = new LinkedHashMap<String, String>();
        fields.put("user_id", Long.toString(userId));
        fields.put("org_member_id", Long.toString(organizationMemberId));
        fields.put("security_version", Long.toString(securityVersion));
        fields.put("current_token_hash", TokenDigest.sha256(secret));
        fields.put("previous_token_hash", "");
        fields.put("previous_valid_until_ms", "0");
        fields.put("created_at_ms", Long.toString(now));
        fields.put("last_rotated_at_ms", Long.toString(now));
        fields.put("absolute_expires_at_ms", Long.toString(now + sessionLifetime.toMillis()));
        fields.put("device_label", deviceLabel == null ? "" : deviceLabel);
        var key = key(sessionId);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, sessionLifetime);
        return new RefreshCredential(sessionId + "." + secret);
    }

    public RefreshRotationResult rotate(String rawCredential) {
        var parsed = parse(rawCredential);
        if (parsed == null) {
            return new RefreshRotationResult(RefreshRotationOutcome.EXPIRED, null);
        }
        var nextSecret = randomPart();
        var outcomeValue = redis.execute(
                ROTATION_SCRIPT,
                List.of(key(parsed.sessionId())),
                TokenDigest.sha256(parsed.secret()),
                TokenDigest.sha256(nextSecret),
                Long.toString(clock.millis()),
                Long.toString(raceWindow.toMillis()));
        var outcome = outcomeValue == null
                ? RefreshRotationOutcome.EXPIRED
                : RefreshRotationOutcome.valueOf(outcomeValue);
        var next = outcome == RefreshRotationOutcome.ROTATED
                ? parsed.sessionId() + "." + nextSecret
                : null;
        return new RefreshRotationResult(outcome, next);
    }

    public RefreshSessionData load(String rawCredential) {
        var parsed = parse(rawCredential);
        if (parsed == null) {
            return null;
        }
        Map<Object, Object> values = redis.opsForHash().entries(key(parsed.sessionId()));
        if (values.isEmpty()) {
            return null;
        }
        return new RefreshSessionData(
                Long.parseLong((String) values.get("user_id")),
                Long.parseLong((String) values.get("org_member_id")),
                Long.parseLong((String) values.get("security_version")));
    }

    public void revoke(String rawCredential) {
        var parsed = parse(rawCredential);
        if (parsed != null) {
            redis.delete(key(parsed.sessionId()));
        }
    }

    private String randomPart() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ParsedCredential parse(String rawCredential) {
        if (rawCredential == null) {
            return null;
        }
        var separator = rawCredential.indexOf('.');
        if (separator < 1 || separator == rawCredential.length() - 1
                || rawCredential.indexOf('.', separator + 1) >= 0) {
            return null;
        }
        return new ParsedCredential(rawCredential.substring(0, separator), rawCredential.substring(separator + 1));
    }

    private static String key(String sessionId) {
        return PREFIX + sessionId;
    }

    private record ParsedCredential(String sessionId, String secret) {
    }
}
