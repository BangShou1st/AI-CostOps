package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import java.time.Clock;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogoutService {
    private final RedisRefreshSessionRepository sessions;
    private final IamMapper iam;
    private final SecurityVersionService versions;
    private final AuditService audit;
    private final Clock clock;

    public LogoutService(RedisRefreshSessionRepository sessions, IamMapper iam,
            SecurityVersionService versions, AuditService audit, Clock clock) {
        this.sessions = sessions; this.iam = iam; this.versions = versions; this.audit = audit; this.clock = clock;
    }

    public void logout(long userId, String refreshCredential) {
        try { sessions.revoke(refreshCredential); } catch (DataAccessException ignored) { }
        audit.append("LOGOUT", null, userId, "USER", userId, Map.of("scope", "CURRENT_SESSION"));
    }

    @Transactional
    public void logoutAll(long userId) {
        if (iam.incrementSecurityVersion(userId, clock.instant()) == 0) return;
        versions.invalidate(userId);
        try { sessions.revokeAll(userId); } catch (DataAccessException ignored) { }
        audit.append("SESSION_REVOKED", null, userId, "USER", userId, Map.of("reason", "LOGOUT_ALL"));
    }
}
