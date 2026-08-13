package com.aicostops.iam.application;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.infrastructure.AuthorizationContextMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationContextService {

    private final AuthorizationContextMapper authorizationContextMapper;
    private final AuthorizationContextCache authorizationContextCache;

    public AuthorizationContextService(
            AuthorizationContextMapper authorizationContextMapper,
            AuthorizationContextCache authorizationContextCache) {
        this.authorizationContextMapper = authorizationContextMapper;
        this.authorizationContextCache = authorizationContextCache;
    }

    public AuthorizationContext current(AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) throw sessionExpired();
        try {
            var cached = authorizationContextCache.get(
                    authenticatedUser.userId(), authenticatedUser.securityVersion());
            if (cached != null) {
                return cached;
            }
        } catch (DataAccessException | IllegalArgumentException ignored) {
            // MySQL remains the durable authorization truth.
        }
        var context = load(authenticatedUser);
        try {
            authorizationContextCache.put(context);
        } catch (DataAccessException ignored) {
            // A cache outage must not deny an otherwise valid request.
        }
        return context;
    }

    public AuthorizationContext fresh(AuthenticatedUser authenticatedUser) {
        return load(authenticatedUser);
    }

    private AuthorizationContext load(AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) throw sessionExpired();
        var identity = authorizationContextMapper.findIdentity(authenticatedUser.userId());
        if (identity == null || identity.securityVersion() != authenticatedUser.securityVersion()) {
            throw sessionExpired();
        }
        var grantRecords = authorizationContextMapper.findGrants(identity.organizationMemberId());
        var grants = grantRecords.stream()
                .map(grant -> grant.toDomain())
                .collect(Collectors.toUnmodifiableSet());
        var roleCodes = grantRecords.stream()
                .map(grant -> grant.roleCode())
                .collect(Collectors.toUnmodifiableSet());
        return new AuthorizationContext(identity.userId(), identity.organizationId(),
                identity.organizationMemberId(), identity.securityVersion(), grants, roleCodes);
    }

    private DomainException sessionExpired() {
        return new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                "Authentication session expired", "Sign in again.");
    }
}
