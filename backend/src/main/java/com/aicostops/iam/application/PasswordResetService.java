package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.domain.EmailAddress;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.RedisPasswordResetRepository;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
    private final IamMapper iam; private final RedisPasswordResetRepository resets;
    private final RedisRefreshSessionRepository sessions; private final PasswordResetDelivery delivery;
    private final PasswordEncoder passwords; private final SecurityVersionService versions;
    private final AuditService audit; private final Clock clock; private final StringRedisTemplate redis;
    private final int limit;

    public PasswordResetService(IamMapper iam, RedisPasswordResetRepository resets,
            RedisRefreshSessionRepository sessions, PasswordResetDelivery delivery, PasswordEncoder passwords,
            SecurityVersionService versions, AuditService audit, Clock clock, StringRedisTemplate redis,
            @org.springframework.beans.factory.annotation.Value("${aicostops.auth.password-reset-limit:5}") int limit) {
        this.iam=iam; this.resets=resets; this.sessions=sessions; this.delivery=delivery; this.passwords=passwords;
        this.versions=versions; this.audit=audit; this.clock=clock; this.redis=redis; this.limit=limit;
    }

    public void forgot(String email, String remoteIp) {
        var normalized = EmailAddress.normalize(email);
        var key = "aicostops:v1:ratelimit:password-reset:" +
                com.aicostops.iam.domain.TokenDigest.sha256(normalized + "|" + remoteIp);
        final Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, java.time.Duration.ofMinutes(15));
        } catch (DataAccessException exception) {
            throw redisUnavailable();
        }
        if (count != null && count > limit) throw new DomainException(HttpStatus.TOO_MANY_REQUESTS,
                ProblemCode.AUTH_RATE_LIMITED, "Too many reset attempts", "Try again later.", 900L);
        var identity = iam.findPasswordResetIdentity(normalized);
        if (identity != null && "ACTIVE".equals(identity.status())) {
            final String token;
            try { token = resets.create(identity.userId()); }
            catch (DataAccessException exception) { throw redisUnavailable(); }
            delivery.deliver(normalized, token);
        }
    }

    @Transactional
    public void reset(String token, String newPassword) {
        final Long userId;
        try { userId = resets.consume(token); }
        catch (DataAccessException exception) { throw redisUnavailable(); }
        if (userId == null) throw expired();
        var identity = iam.findPasswordResetIdentityById(userId);
        if (identity == null || !"ACTIVE".equals(identity.status())) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.ACCOUNT_DISABLED,
                    "Account disabled", "This account is disabled.");
        }
        var now = clock.instant();
        iam.updatePassword(userId, passwords.encode(newPassword), now);
        iam.incrementSecurityVersion(userId, now);
        versions.invalidate(userId);
        try { sessions.revokeAll(userId); } catch (DataAccessException ignored) { }
        audit.append("PASSWORD_CHANGED", null, userId, "USER", userId, Map.of("method", "RESET"));
    }

    private DomainException expired() {
        return new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                "Reset session expired", "Request a new password reset.");
    }

    private DomainException redisUnavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE, ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH,
                "Authentication runtime unavailable", "Authentication is temporarily unavailable.");
    }
}
