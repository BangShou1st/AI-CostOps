package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.AuthorizationContextCache;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class RedisAuthorizationContextCache implements AuthorizationContextCache {

    private static final String PREFIX = "aicostops:v1:iam:context:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisAuthorizationContextCache(StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public AuthorizationContext get(long userId, long securityVersion) {
        var value = redis.opsForValue().get(key(userId, securityVersion));
        if (value == null) {
            return null;
        }
        return deserialize(value, userId, securityVersion);
    }

    @Override
    public void put(AuthorizationContext context) {
        redis.opsForValue().set(key(context.userId(), context.securityVersion()),
                serialize(context), ttl);
    }

    @Override
    public void evict(long userId, long securityVersion) {
        redis.delete(key(userId, securityVersion));
    }

    private String serialize(AuthorizationContext context) {
        try {
            var grants = context.grants().stream()
                    .map(grant -> new CachedGrant(
                            grant.permissionCode(), grant.scopeType(), grant.scopeId()))
                    .collect(Collectors.toUnmodifiableSet());
            return objectMapper.writeValueAsString(new CachedAuthorizationContext(
                    context.userId(), context.organizationId(), context.organizationMemberId(),
                    context.securityVersion(), grants, context.roleCodes()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize authorization context", exception);
        }
    }

    private AuthorizationContext deserialize(String value, long expectedUserId, long expectedSecurityVersion) {
        try {
            var cached = objectMapper.readValue(value, CachedAuthorizationContext.class);
            validate(cached, expectedUserId, expectedSecurityVersion);
            var grants = cached.grants().stream()
                    .map(grant -> new ScopedPermissionGrant(
                            grant.permissionCode(), grant.scopeType(), grant.scopeId()))
                    .collect(Collectors.toUnmodifiableSet());
            return new AuthorizationContext(cached.userId(), cached.organizationId(),
                    cached.organizationMemberId(), cached.securityVersion(), grants, cached.roleCodes());
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Malformed authorization context cache value", exception);
        }
    }

    private static void validate(
            CachedAuthorizationContext cached, long expectedUserId, long expectedSecurityVersion) {
        Objects.requireNonNull(cached, "Cached authorization context is required");
        if (cached.userId() != expectedUserId || cached.securityVersion() != expectedSecurityVersion
                || cached.userId() <= 0 || cached.organizationId() <= 0
                || cached.organizationMemberId() <= 0 || cached.securityVersion() < 0) {
            throw new IllegalArgumentException("Cached authorization context identity is invalid");
        }
        Objects.requireNonNull(cached.grants(), "Cached grants are required");
        Objects.requireNonNull(cached.roleCodes(), "Cached role codes are required");
        cached.grants().forEach(grant -> {
            Objects.requireNonNull(grant, "Cached grant is required");
            if (grant.permissionCode() == null || grant.permissionCode().isBlank()
                    || grant.scopeType() == null || grant.scopeId() <= 0) {
                throw new IllegalArgumentException("Cached grant is invalid");
            }
        });
        if (cached.roleCodes().stream().anyMatch(roleCode -> roleCode == null || roleCode.isBlank())) {
            throw new IllegalArgumentException("Cached role code is invalid");
        }
    }

    private static String key(long userId, long securityVersion) {
        return PREFIX + userId + ":" + securityVersion;
    }

    private record CachedAuthorizationContext(
            long userId,
            long organizationId,
            long organizationMemberId,
            long securityVersion,
            Set<CachedGrant> grants,
            Set<String> roleCodes) {
    }

    private record CachedGrant(String permissionCode, ScopeType scopeType, long scopeId) {
    }
}
