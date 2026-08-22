package com.aicostops.reporting.infrastructure;

import com.aicostops.reporting.application.DashboardCachePort;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis cache-aside adapter. Any Redis failure (connection refused, timeout,
 * serialization problem) degrades to a cache miss — the workbench is served
 * from MySQL and stale entries simply expire by TTL.
 */
@Component
public class RedisDashboardCacheAdapter implements DashboardCachePort {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisDashboardCacheAdapter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T read(String key, Class<T> type) {
        try {
            var json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, type);
        } catch (DataAccessException | JacksonException degraded) {
            return null;
        }
    }

    @Override
    public void write(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (DataAccessException | JacksonException ignored) {
            // Cache writes are best-effort; MySQL remains the source of truth.
        }
    }
}
