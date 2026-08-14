package com.aicostops.organization.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.application.ResourceScope;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.organization.api.CostCenterResponse;
import com.aicostops.organization.api.CreateCostCenterRequest;
import com.aicostops.organization.api.UpdateCostCenterRequest;
import com.aicostops.organization.domain.MasterDataStatus;
import com.aicostops.organization.infrastructure.CostCenterMapper;
import com.aicostops.organization.infrastructure.CostCenterMapper.ScopeParameters;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CostCenterService {

    private final AuthorizationContextService authorizationContexts;
    private final CostCenterMapper mapper;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public CostCenterService(
            AuthorizationContextService authorizationContexts,
            CostCenterMapper mapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PageResponse<CostCenterResponse> list(
            AuthenticatedUser authenticatedUser, MasterDataStatus status, PageRequest page) {
        var context = authorizationContexts.current(authenticatedUser);
        var scope = scopeParameters(authorization.requireList(
                context, "COST_CENTER_READ", ScopeType.COST_CENTER));
        var statusValue = status == null ? null : status.name();
        var total = mapper.countAuthorized(context.organizationId(), statusValue, scope);
        var costCenters = mapper.findAuthorizedPage(context.organizationId(), statusValue, scope,
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(costCenters.stream().map(CostCenterResponse::from).toList(), page, total);
    }

    @Transactional
    public CostCenterResponse create(
            AuthenticatedUser authenticatedUser, CreateCostCenterRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "COST_CENTER_MANAGE");
        var code = normalize(request.code(), 100, "Cost center code");
        var name = normalize(request.name(), 200, "Cost center name");
        var now = clock.instant();
        try {
            if (mapper.insert(context.organizationId(), code, name, now) != 1) {
                throw new IllegalStateException("Cost center creation must insert exactly one row");
            }
        } catch (DuplicateKeyException exception) {
            throw conflict("Cost center code conflict",
                    "The cost center code already exists in the current organization.");
        }
        var costCenter = mapper.findCurrentOrganizationCostCenter(
                mapper.lastInsertId(), context.organizationId());
        if (costCenter == null) {
            throw new IllegalStateException("Created cost center must be readable in its organization");
        }
        return CostCenterResponse.from(costCenter);
    }

    @Transactional
    public CostCenterResponse update(
            AuthenticatedUser authenticatedUser, long costCenterId, UpdateCostCenterRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        var scope = scopeParameters(authorization.requireList(
                context, "COST_CENTER_MANAGE", ScopeType.COST_CENTER));
        var costCenter = mapper.findAuthorizedForUpdate(
                context.organizationId(), null, scope, costCenterId);
        if (costCenter == null) {
            throw notFound();
        }
        if (request.name() == null && request.status() == null) {
            throw validationFailed("A cost center name or status is required.");
        }
        var name = request.name() == null
                ? costCenter.name()
                : normalize(request.name(), 200, "Cost center name");
        var status = request.status() == null ? costCenter.status() : request.status();
        if (request.status() != null && !costCenter.status().canTransitionTo(request.status())) {
            throw conflict("Cost center status conflict",
                    "The requested cost center status transition is not allowed.");
        }
        if (mapper.updateAuthorized(costCenterId, context.organizationId(), null, scope,
                name, status.name(), clock.instant()) != 1) {
            throw notFound();
        }
        var updated = mapper.findCurrentOrganizationCostCenter(costCenterId, context.organizationId());
        if (updated == null) {
            throw notFound();
        }
        return CostCenterResponse.from(updated);
    }

    private ScopeParameters scopeParameters(ResourceScope scope) {
        return new ScopeParameters(
                scope.organizationWide(), scope.resourceIds().stream().sorted().toList());
    }

    private String normalize(String value, int maxLength, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw validationFailed(field + " must be nonblank and at most "
                    + maxLength + " characters.");
        }
        return value.trim();
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Cost center validation failed", detail);
    }

    private DomainException conflict(String title, String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT, title, detail);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", "The cost center is not available in the current organization.");
    }

}
