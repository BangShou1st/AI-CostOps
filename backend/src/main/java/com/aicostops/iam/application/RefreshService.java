package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.iam.infrastructure.RefreshRotationOutcome;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Service
public class RefreshService {
    private final RedisRefreshSessionRepository sessions;
    private final IamMapper iam;
    private final JwtTokenService tokens;
    private final AuditService audit;

    public RefreshService(RedisRefreshSessionRepository sessions, IamMapper iam,
            JwtTokenService tokens, AuditService audit) {
        this.sessions = sessions; this.iam = iam; this.tokens = tokens; this.audit = audit;
    }

    public LoginResult refresh(String credential) {
        final com.aicostops.iam.infrastructure.RefreshSessionData before;
        final com.aicostops.iam.infrastructure.RefreshRotationResult rotation;
        try {
            before = sessions.load(credential);
            rotation = sessions.rotate(credential);
        } catch (DataAccessException exception) {
            throw redisUnavailable();
        }
        if (rotation.outcome() == RefreshRotationOutcome.RACE) {
            throw problem(HttpStatus.CONFLICT, ProblemCode.AUTH_REFRESH_RACE,
                    "Refresh already in progress", "Retry refresh once after a brief wait.");
        }
        if (rotation.outcome() == RefreshRotationOutcome.REPLAY) {
            if (before != null) audit.append("SESSION_REVOKED", null, before.userId(), "USER", before.userId(),
                    Map.of("reason", "REFRESH_REPLAY"));
            throw problem(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_REFRESH_REPLAY,
                    "Refresh token replayed", "The refresh session has been revoked.");
        }
        if (rotation.outcome() == RefreshRotationOutcome.EXPIRED) {
            throw problem(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                    "Refresh session expired", "Sign in again.");
        }
        final com.aicostops.iam.infrastructure.RefreshSessionData session;
        try { session = sessions.load(rotation.nextCredential()); }
        catch (DataAccessException exception) { throw redisUnavailable(); }
        var identity = iam.findAuthenticatedIdentity(session.userId());
        if (identity == null || identity.securityVersion() != session.securityVersion()) {
            try { sessions.revoke(rotation.nextCredential()); }
            catch (DataAccessException exception) { throw redisUnavailable(); }
            throw problem(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                    "Refresh session expired", "Sign in again.");
        }
        return new LoginResult(tokens.issue(identity.userId(), identity.securityVersion()),
                rotation.nextCredential(), identity.userId(), identity.displayName(),
                identity.organizationMemberId(), identity.organizationId());
    }

    private DomainException problem(HttpStatus status, ProblemCode code, String title, String detail) {
        return new DomainException(status, code, title, detail);
    }

    private DomainException redisUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemCode.REDIS_UNAVAILABLE_FOR_AUTH,
                "Authentication runtime unavailable", "Authentication is temporarily unavailable.");
    }
}
