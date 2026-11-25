package com.aicostops.gateway.request;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Coordinates the initial insert. Request state and fingerprints always come from MySQL. */
@Component
class GatewayIdempotencyHint {
    private static final Logger log = LoggerFactory.getLogger(GatewayIdempotencyHint.class);
    private static final Duration TTL = Duration.ofDays(1);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            if ARGV[2] == '' then
                return redis.call('DEL', KEYS[1])
            end
            redis.call('SET', KEYS[1], ARGV[2], 'XX', 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    GatewayIdempotencyHint(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(long credentialId, byte[] digest) {
        return "aicostops:v2:gateway:idem:" + credentialId + ":" + HexFormat.of().formatHex(digest);
    }

    Claim claim(String key, String owner) {
        try {
            var acquired = redis.opsForValue().setIfAbsent(key, owner, TTL);
            if (acquired != null) {
                return acquired ? Claim.OWNED : Claim.CONTENDED;
            }
        } catch (DataAccessException ex) {
            log.debug("Could not claim Gateway idempotency hint: {}", ex.getClass().getSimpleName());
        }
        return Claim.UNAVAILABLE;
    }

    void finish(String key, String owner, Long requestId) {
        try {
            redis.execute(FINISH, List.of(key), owner,
                    requestId == null ? "" : requestId.toString(), Long.toString(TTL.toMillis()));
        } catch (DataAccessException ex) {
            log.debug("Could not finish Gateway idempotency hint: {}", ex.getClass().getSimpleName());
        }
    }

    enum Claim { OWNED, CONTENDED, UNAVAILABLE }
}
