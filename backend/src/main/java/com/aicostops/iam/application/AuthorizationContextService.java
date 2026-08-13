package com.aicostops.iam.application;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.infrastructure.AuthorizationContextMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationContextService {

    private final AuthorizationContextMapper authorizationContextMapper;

    public AuthorizationContextService(AuthorizationContextMapper authorizationContextMapper) {
        this.authorizationContextMapper = authorizationContextMapper;
    }

    public AuthorizationContext fresh(AuthenticatedUser authenticatedUser) {
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
                identity.organizationMemberId(), identity.securityVersion(), roleCodes, grants);
    }

    private DomainException sessionExpired() {
        return new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                "Authentication session expired", "Sign in again.");
    }
}
