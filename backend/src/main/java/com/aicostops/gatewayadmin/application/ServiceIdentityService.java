package com.aicostops.gatewayadmin.application;

import com.aicostops.gatewayadmin.api.ControlPlaneDtos;
import com.aicostops.gatewayadmin.infrastructure.ControlPlaneMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Govened Service Identity lifecycle (browser UAT-01). Org-scoped list/create
 * and status view. Reuses the provider/gateway administration permission
 * family that already governs routing policies and provider accounts; no new
 * permission seed and therefore no migration is required.
 */
@Service
public class ServiceIdentityService {

    private final AuthorizationContextService authorizationContexts;
    private final ControlPlaneMapper mapper;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final GatewayAdminAuditPort audit;

    public ServiceIdentityService(AuthorizationContextService authorizationContexts,
            ControlPlaneMapper mapper, GatewayAdminAuditPort audit) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.audit = audit;
    }

    public List<ControlPlaneDtos.ServiceIdentityResponse> list(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return mapper.selectServiceIdentities(context.organizationId()).stream()
                .map(row -> new ControlPlaneDtos.ServiceIdentityResponse(
                        ApiId.of(row.id()), row.code(), row.name(), row.status(), row.createdAt()))
                .toList();
    }

    public ControlPlaneDtos.ServiceIdentityResponse get(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var row = mapper.selectServiceIdentity(context.organizationId(), id);
        if (row == null) {
            throw resourceNotFound();
        }
        return new ControlPlaneDtos.ServiceIdentityResponse(
                ApiId.of(row.id()), row.code(), row.name(), row.status(), row.createdAt());
    }

    @Transactional
    public ControlPlaneDtos.ServiceIdentityResponse create(AuthenticatedUser user,
            ControlPlaneDtos.ServiceIdentityRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var code = request.code().strip().toLowerCase();
        if (!code.matches("[a-z0-9][a-z0-9-]{0,99}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Service identity code is invalid",
                    "Code must start with a lowercase letter or digit and contain only lowercase letters, digits and dashes.");
        }
        mapper.insertServiceIdentity(context.organizationId(), code, request.name().strip());
        long id = mapper.lastInsertId();
        audit.serviceIdentityCreated(context.organizationId(), user.userId(), id, code, "ACTIVE");
        return get(user, id);
    }

    private static DomainException resourceNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The resource is not available at the granted scope.");
    }
}