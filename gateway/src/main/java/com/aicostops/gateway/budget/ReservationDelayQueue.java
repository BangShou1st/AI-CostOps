package com.aicostops.gateway.budget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Best-effort wakeups for durable MySQL reservations. */
@Component
public class ReservationDelayQueue {
    private static final Logger log = LoggerFactory.getLogger(ReservationDelayQueue.class);
    private static final String KEY = "aicostops:v2:gateway:reservation-expiry";
    private static final DefaultRedisScript<List> CLAIM = new DefaultRedisScript<>("""
            local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            for _, id in ipairs(ids) do
                redis.call('ZREM', KEYS[1], id)
            end
            return ids
            """, List.class);

    private final StringRedisTemplate redis;

    public ReservationDelayQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void enqueue(long reservationId, Instant expiresAt) {
        try {
            redis.opsForZSet().add(KEY, Long.toString(reservationId), expiresAt.toEpochMilli());
        } catch (DataAccessException ex) {
            log.debug("Could not enqueue reservation expiry: {}", ex.getClass().getSimpleName());
        }
    }

    public void remove(long reservationId) {
        try {
            redis.opsForZSet().remove(KEY, Long.toString(reservationId));
        } catch (DataAccessException ex) {
            log.debug("Could not remove reservation expiry: {}", ex.getClass().getSimpleName());
        }
    }

    List<Long> claimDue(Instant now, int limit) {
        List<?> members;
        try {
            members = redis.execute(CLAIM, List.of(KEY), Long.toString(now.toEpochMilli()),
                    Integer.toString(limit));
        } catch (DataAccessException ex) {
            log.debug("Could not claim reservation expiries: {}", ex.getClass().getSimpleName());
            return List.of();
        }
        if (members == null) {
            return List.of();
        }
        var ids = new ArrayList<Long>(members.size());
        for (var member : members) {
            try {
                ids.add(Long.parseLong((String) member));
            } catch (NumberFormatException ex) {
                log.debug("Discarded malformed reservation expiry member");
            }
        }
        return ids;
    }
}
