package com.aicostops.iam.application;

import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthorizationInvalidationService {
    private final IamMapper iamMapper;
    private final SecurityVersionService securityVersions;
    private final AuthorizationContextCache authorizationContexts;
    private final RedisRefreshSessionRepository refreshSessions;
    private final Clock clock;

    public AuthorizationInvalidationService(
            IamMapper iamMapper,
            SecurityVersionService securityVersions,
            AuthorizationContextCache authorizationContexts,
            RedisRefreshSessionRepository refreshSessions,
            Clock clock) {
        this.iamMapper = iamMapper;
        this.securityVersions = securityVersions;
        this.authorizationContexts = authorizationContexts;
        this.refreshSessions = refreshSessions;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long bumpInTransaction(long targetUserId, long oldVersion) {
        var updated = iamMapper.incrementSecurityVersionForAuthorizationChange(
                targetUserId, clock.instant());
        if (updated != 1) {
            throw new IllegalStateException("Authorization change must update exactly one user");
        }
        var newVersion = iamMapper.findSecurityVersionById(targetUserId);
        if (newVersion == null) {
            throw new IllegalStateException("Updated user security version is unavailable");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                bestEffort(() -> securityVersions.put(targetUserId, newVersion));
                bestEffort(() -> authorizationContexts.evict(targetUserId, oldVersion));
                bestEffort(() -> authorizationContexts.evict(targetUserId, newVersion));
                bestEffort(() -> refreshSessions.revokeAll(targetUserId));
            }
        });
        return newVersion;
    }

    private static void bestEffort(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // MySQL already committed the durable security version.
        }
    }
}
