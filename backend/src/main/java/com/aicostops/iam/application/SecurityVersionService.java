package com.aicostops.iam.application;

import com.aicostops.iam.infrastructure.IamMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SecurityVersionService {
    private static final String PREFIX = "aicostops:v1:auth:security:";
    private final StringRedisTemplate redis;
    private final IamMapper iamMapper;
    private final Duration ttl;

    public SecurityVersionService(StringRedisTemplate redis, IamMapper iamMapper,
            @Value("${aicostops.auth.security-cache-ttl:1m}") Duration ttl) {
        this.redis = redis;
        this.iamMapper = iamMapper;
        this.ttl = ttl;
    }

    public Long current(long userId) {
        try {
            var cached = redis.opsForValue().get(PREFIX + userId);
            if (cached != null) return Long.parseLong(cached);
        } catch (DataAccessException ignored) {
            // MySQL remains the durable authentication truth.
        }
        var current = iamMapper.findActiveSecurityVersion(userId);
        if (current != null) {
            try { redis.opsForValue().set(PREFIX + userId, current.toString(), ttl); }
            catch (DataAccessException ignored) { }
        }
        return current;
    }

    public void invalidate(long userId) {
        try { redis.delete(PREFIX + userId); }
        catch (DataAccessException ignored) { }
    }
}
