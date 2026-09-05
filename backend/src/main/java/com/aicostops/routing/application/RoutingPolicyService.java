package com.aicostops.routing.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.routing.api.RoutingPolicyDtos;
import com.aicostops.routing.domain.RoutingPolicy;
import com.aicostops.routing.domain.RoutingPolicyCandidate;
import com.aicostops.routing.domain.RoutingPolicyStatus;
import com.aicostops.routing.infrastructure.RoutingPolicyMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingPolicyService {

    private final AuthorizationContextService authorizationContexts;
    private final RoutingPolicyMapper mapper;
    private final Clock clock;
    private final com.aicostops.organization.application.OrganizationAuditPort audit;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public RoutingPolicyService(AuthorizationContextService authorizationContexts,
            RoutingPolicyMapper mapper, Clock clock,
            com.aicostops.organization.application.OrganizationAuditPort audit) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.clock = clock;
        this.audit = audit;
    }

    public PageResponse<RoutingPolicyDtos.PolicyResponse> list(AuthenticatedUser user, PageRequest page) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        var rows = mapper.findPage(context.organizationId(),
                Math.multiplyExact((long) page.page(), page.size()), page.size());
        return PageResponse.of(rows.stream().map(row -> load(context.organizationId(), row)).map(RoutingPolicyDtos.PolicyResponse::from).toList(),
                page, mapper.count(context.organizationId()));
    }

    public RoutingPolicyDtos.PolicyResponse get(AuthenticatedUser user, long policyId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        return RoutingPolicyDtos.PolicyResponse.from(requirePolicy(context.organizationId(), policyId));
    }

    @Transactional
    public RoutingPolicyDtos.PolicyResponse create(AuthenticatedUser user, RoutingPolicyDtos.CreateRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var policy = writeDraft(context.organizationId(), user.userId(), request.projectId(), request.modelId(),
                request.candidates(), "ROUTING_POLICY_CREATED", false);
        return RoutingPolicyDtos.PolicyResponse.from(policy);
    }

    @Transactional
    public RoutingPolicyDtos.PolicyResponse revise(AuthenticatedUser user, long policyId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var source = requirePolicy(context.organizationId(), policyId);
        mapper.lockOrganization(context.organizationId());
        var draft = writeDraftLocked(context.organizationId(), user.userId(), source.projectId(), source.modelId(),
                source.candidates().stream().map(c -> new RoutingPolicyDtos.CandidateInput(
                        c.providerAccountId(), c.providerModelId(), c.priority(), c.status(), c.privacyRegionCode())).toList(),
                "ROUTING_POLICY_REVISED");
        return RoutingPolicyDtos.PolicyResponse.from(draft);
    }

    @Transactional
    public RoutingPolicyDtos.PolicyResponse update(AuthenticatedUser user, long policyId,
            RoutingPolicyDtos.UpdateRequest request) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        var policy = requirePolicyForUpdate(context.organizationId(), policyId);
        if (policy.status() != RoutingPolicyStatus.DRAFT) {
            throw conflict("Only DRAFT routing policies can be edited.");
        }
        validateCandidates(context.organizationId(), policy.modelId(), request.candidates(), false);
        mapper.deleteCandidates(context.organizationId(), policyId);
        insertCandidates(context.organizationId(), policyId, request.candidates());
        var updated = requirePolicy(context.organizationId(), policyId);
        audit.routingPolicyUpdated(context.organizationId(), user.userId(), policyId,
                updated.version(), updated.status().name());
        return RoutingPolicyDtos.PolicyResponse.from(updated);
    }

    @Transactional
    public RoutingPolicyDtos.PolicyResponse activate(AuthenticatedUser user, long policyId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
        mapper.lockOrganization(context.organizationId());
        var policy = requirePolicyForUpdate(context.organizationId(), policyId);
        if (policy.status() != RoutingPolicyStatus.DRAFT) {
            throw conflict("Only DRAFT routing policies can be activated.");
        }
        validateCandidates(context.organizationId(), policy.modelId(),
                policy.candidates().stream().map(c -> new RoutingPolicyDtos.CandidateInput(
                        c.providerAccountId(), c.providerModelId(), c.priority(), c.status(), c.privacyRegionCode())).toList(), true);
        mapper.retireActiveExactScope(context.organizationId(), policy.projectId(), policy.modelId());
        if (mapper.activateDraft(context.organizationId(), policyId, clock.instant()) != 1) {
            throw conflict("The routing policy changed before activation completed.");
        }
        var active = requirePolicy(context.organizationId(), policyId);
        audit.routingPolicyActivated(context.organizationId(), user.userId(), policyId,
                active.version(), active.status().name());
        return RoutingPolicyDtos.PolicyResponse.from(active);
    }

    public List<RoutingPolicyDtos.RouteOptionResponse> options(AuthenticatedUser user, long modelId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
        if (mapper.findActiveModel(modelId) == null) {
            throw notFound("The model is not available.");
        }
        return mapper.findRouteOptions(context.organizationId(), modelId, clock.instant()).stream()
                .map(row -> new RoutingPolicyDtos.RouteOptionResponse(
                        com.aicostops.shared.json.ApiId.of(row.providerAccountId()), row.displayName(), row.providerCode(),
                        com.aicostops.shared.json.ApiId.of(row.providerModelId()), row.providerModelName(),
                        row.routingEligible(), row.credentialReady(), row.pricingReady(),
                        row.currencies() == null || row.currencies().isBlank()
                                ? List.of() : Arrays.stream(row.currencies().split(",")).toList()))
                .toList();
    }

    private RoutingPolicy writeDraft(long organizationId, long actorUserId, Long projectId, long modelId,
            List<RoutingPolicyDtos.CandidateInput> candidates, String eventType, boolean alreadyLocked) {
        if (!alreadyLocked) {
            mapper.lockOrganization(organizationId);
        }
        return writeDraftLocked(organizationId, actorUserId, projectId, modelId, candidates, eventType);
    }

    private RoutingPolicy writeDraftLocked(long organizationId, long actorUserId, Long projectId, long modelId,
            List<RoutingPolicyDtos.CandidateInput> candidates, String eventType) {
        if (projectId != null && mapper.findActiveProject(organizationId, projectId) == null) {
            throw notFound("The project is not available in the current organization.");
        }
        if (mapper.findActiveModel(modelId) == null) {
            throw notFound("The model is not available.");
        }
        validateCandidates(organizationId, modelId, candidates, false);
        int version = mapper.nextVersion(organizationId, projectId, modelId);
        if (mapper.insertPolicy(organizationId, projectId, modelId, version, clock.instant()) != 1) {
            throw new IllegalStateException("Routing policy creation must insert exactly one row");
        }
        long policyId = mapper.lastInsertId();
        insertCandidates(organizationId, policyId, candidates);
        var policy = requirePolicy(organizationId, policyId);
        if (eventType.equals("ROUTING_POLICY_REVISED")) {
            audit.routingPolicyRevised(organizationId, actorUserId, policyId, version, policy.status().name());
        } else {
            audit.routingPolicyCreated(organizationId, actorUserId, policyId, version, policy.status().name());
        }
        return policy;
    }

    private void insertCandidates(long organizationId, long policyId, List<RoutingPolicyDtos.CandidateInput> candidates) {
        for (var candidate : candidates) {
            mapper.insertCandidate(organizationId, policyId, candidate.providerAccountId(), candidate.providerModelId(),
                    candidate.priority(), normalizeStatus(candidate.status()), normalizeRegion(candidate.privacyRegionCode()), clock.instant());
        }
    }

    private void validateCandidates(long organizationId, long modelId,
            List<RoutingPolicyDtos.CandidateInput> candidates, boolean activation) {
        if (candidates == null || candidates.isEmpty()) {
            if (activation) throw validation("At least one active route candidate is required.");
            return;
        }
        long active = 0;
        var unique = new java.util.HashSet<String>();
        for (var candidate : candidates) {
            var key = candidate.providerAccountId() + ":" + candidate.providerModelId();
            if (!unique.add(key)) throw validation("A route candidate may appear only once.");
            var accountCode = mapper.findActiveAccountProviderCode(organizationId, candidate.providerAccountId());
            var modelCode = mapper.findEligibleProviderModelCode(candidate.providerModelId(), modelId);
            if (accountCode == null || modelCode == null || !accountCode.equals(modelCode)
                    || !mapper.isActiveProviderCatalog(accountCode)) {
                throw validation("Provider account and provider model must be active and use the same provider code.");
            }
            var status = normalizeStatus(candidate.status());
            if (status.equals("ACTIVE")) active++;
        }
        if (activation && active == 0) throw validation("At least one active route candidate is required.");
    }

    private RoutingPolicy requirePolicy(long organizationId, long policyId) {
        var row = mapper.find(policyId, organizationId);
        if (row == null) throw notFound("The routing policy is not available in the current organization.");
        return load(organizationId, row);
    }

    private RoutingPolicy requirePolicyForUpdate(long organizationId, long policyId) {
        var row = mapper.findForUpdate(policyId, organizationId);
        if (row == null) throw notFound("The routing policy is not available in the current organization.");
        return load(organizationId, row);
    }

    private RoutingPolicy load(long organizationId, RoutingPolicyMapper.PolicyRow row) {
        return new RoutingPolicy(row.id(), row.organizationId(), row.projectId(), row.modelId(), row.version(),
                RoutingPolicyStatus.valueOf(row.status()), mapper.findCandidates(row.id(), organizationId).stream()
                        .map(c -> new RoutingPolicyCandidate(c.id(), c.providerAccountId(), c.providerModelId(),
                                c.priority(), c.status(), c.privacyRegionCode())).toList());
    }

    private String normalizeStatus(String value) {
        var normalized = value == null ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ACTIVE") && !normalized.equals("DISABLED")) {
            throw validation("Candidate status must be ACTIVE or DISABLED.");
        }
        return normalized;
    }

    private String normalizeRegion(String value) {
        if (value == null) return null;
        var normalized = value.trim();
        if (normalized.length() > 64) throw validation("Privacy region code is too long.");
        return normalized.isEmpty() ? null : normalized;
    }

    private DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Routing policy validation failed", detail);
    }

    private DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Routing policy state conflict", detail);
    }

    private DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Routing policy not found", detail);
    }
}
