package com.aicostops.audit.application;

import com.aicostops.audit.infrastructure.AuditMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-only audit event query (AIC-065). The query is pinned to the caller's
 * own organization: {@code AUDIT_READ} exists only at ORG scope, and the
 * requested organization must be the caller's current one — a different
 * {@code orgId} is a privacy-preserving 404 before any data is touched, so
 * the parameter can never widen visibility across tenants.
 */
@Service
public class AuditQueryService {

    static final String PERMISSION_AUDIT_READ = "AUDIT_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AuditMapper mapper;

    public AuditQueryService(
            AuthorizationContextService authorizationContexts,
            AuditMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public PageResponse<AuditEventView> search(AuthenticatedUser user, PageRequest page,
            long organizationId, String eventType, Instant from, Instant to) {
        var context = authorizationContexts.current(user);
        // AUDIT_READ is applicable at ORG scope only; requireOrg additionally
        // rejects grants whose scope id is not the caller's own organization.
        authorization.requireOrg(context, PERMISSION_AUDIT_READ);
        requireOwnOrganization(context, organizationId);

        var total = mapper.count(organizationId, eventType, from, to);
        if (total == 0) {
            return PageResponse.of(List.of(), page, 0);
        }
        var events = mapper.selectPage(organizationId, eventType, from, to,
                page.size(), Math.multiplyExact(page.page(), page.size()));
        return PageResponse.of(events, page, total);
    }

    private static void requireOwnOrganization(AuthorizationContext context, long organizationId) {
        var ownsOrganization = context.grants().stream()
                .anyMatch(grant -> grant.permissionCode().equals(PERMISSION_AUDIT_READ)
                        && grant.scopeType() == com.aicostops.iam.domain.ScopeType.ORG
                        && grant.scopeId() == context.organizationId());
        if (organizationId != context.organizationId() || !ownsOrganization) {
            throw new com.aicostops.shared.web.DomainException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    com.aicostops.shared.web.ProblemCode.RESOURCE_NOT_FOUND,
                    "Organization not found",
                    "The audit events are not available in the current organization.");
        }
    }
}
