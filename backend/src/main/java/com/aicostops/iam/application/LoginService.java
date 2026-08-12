package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.domain.EmailAddress;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.iam.infrastructure.RedisRateLimiter;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {

    private final RedisRateLimiter rateLimiter;
    private final IamMapper iamMapper;
    private final PasswordEncoder passwordEncoder;
    private final RedisRefreshSessionRepository refreshSessions;
    private final JwtTokenService jwtTokenService;
    private final AuditService auditService;

    public LoginService(
            RedisRateLimiter rateLimiter,
            IamMapper iamMapper,
            PasswordEncoder passwordEncoder,
            RedisRefreshSessionRepository refreshSessions,
            JwtTokenService jwtTokenService,
            AuditService auditService) {
        this.rateLimiter = rateLimiter;
        this.iamMapper = iamMapper;
        this.passwordEncoder = passwordEncoder;
        this.refreshSessions = refreshSessions;
        this.jwtTokenService = jwtTokenService;
        this.auditService = auditService;
    }

    public LoginResult login(LoginCommand command) {
        var email = EmailAddress.normalize(command.email());
        var decision = rateLimiter.checkLogin(command.remoteIp(), email);
        if (!decision.allowed()) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, ProblemCode.AUTH_RATE_LIMITED,
                    "Too many login attempts", "Try again after the current login window.",
                    decision.retryAfterSeconds());
        }

        var identity = iamMapper.findLoginIdentity(email);
        if (identity == null || !passwordEncoder.matches(command.password(), identity.passwordHash())) {
            auditService.append("LOGIN_FAILED", identity == null ? null : identity.organizationId(),
                    identity == null ? null : identity.userId(), "USER",
                    identity == null ? null : identity.userId(), Map.of("result", "INVALID_CREDENTIALS"));
            throw invalidCredentials();
        }
        if (!"ACTIVE".equals(identity.status())) {
            auditService.append("LOGIN_FAILED", identity.organizationId(), identity.userId(), "USER",
                    identity.userId(), Map.of("result", "ACCOUNT_DISABLED"));
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.ACCOUNT_DISABLED,
                    "Account disabled", "This account is disabled.");
        }

        try {
            var refresh = refreshSessions.create(identity.userId(), identity.organizationMemberId(),
                    identity.securityVersion(), command.deviceLabel());
            var access = jwtTokenService.issue(identity.userId(), identity.securityVersion());
            auditService.append("LOGIN_SUCCESS", identity.organizationId(), identity.userId(), "USER",
                    identity.userId(), Map.of("result", "SUCCESS"));
            return new LoginResult(access, refresh.value(), identity.userId(), identity.displayName(),
                    identity.organizationMemberId(), identity.organizationId());
        } catch (DataAccessException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH,
                    "Authentication runtime unavailable", "Authentication is temporarily unavailable.");
        }
    }

    private DomainException invalidCredentials() {
        return new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_INVALID_CREDENTIALS,
                "Invalid credentials", "The email or password is invalid.");
    }
}
