package com.aicostops.iam.application;

import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MeService {

    private final AuthorizationContextService authorizationContexts;
    private final IamMapper iamMapper;

    public MeService(AuthorizationContextService authorizationContexts, IamMapper iamMapper) {
        this.authorizationContexts = authorizationContexts;
        this.iamMapper = iamMapper;
    }

    public MeResult me(AuthenticatedUser authenticatedUser) {
        var context = authorizationContexts.current(authenticatedUser);
        var identity = iamMapper.findAuthenticatedIdentity(context.userId());
        if (identity == null) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, ProblemCode.AUTH_SESSION_EXPIRED,
                    "Authentication session expired", "Sign in again.");
        }
        var permissions = context.grants().stream()
                .filter(grant -> M1AdminPermissionPolicy.applicableScopes(grant.permissionCode())
                        .contains(grant.scopeType()))
                .map(grant -> grant.permissionCode())
                .distinct()
                .sorted()
                .toList();
        return new MeResult(identity.userId(), identity.emailNormalized(), identity.displayName(),
                identity.organizationId(), identity.organizationMemberId(), permissions);
    }

    public record MeResult(
            long userId,
            String email,
            String displayName,
            long organizationId,
            long organizationMemberId,
            List<String> permissions) {
    }
}
