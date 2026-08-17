package com.aicostops.allocation.application;

import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.domain.AllocationRule;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Allocation rule reads. Every call requires ALLOCATION_RULE_MANAGE at ORG
 * scope; rules of other organizations are invisible (privacy-preserving 404).
 */
@Service
public class AllocationRuleQueryService {

    private static final String PERMISSION_ALLOCATION_RULE_MANAGE = "ALLOCATION_RULE_MANAGE";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationRuleRepository rules;

    public AllocationRuleQueryService(
            AuthorizationContextService authorizationContexts,
            AllocationRuleRepository rules) {
        this.authorizationContexts = authorizationContexts;
        this.rules = rules;
    }

    public PageResponse<AllocationRule> list(AuthenticatedUser user, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_RULE_MANAGE);
        PageRequest pageRequest;
        try {
            pageRequest = PageRequest.of(page, size);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid page or size",
                    "Page must be zero or greater and size must be between 1 and 200.");
        }
        var items = rules.pageVersions(context.organizationId(),
                pageRequest.size(), pageRequest.page() * pageRequest.size());
        var total = rules.countVersions(context.organizationId());
        return PageResponse.of(items, pageRequest, total);
    }

    public AllocationRule get(AuthenticatedUser user, long ruleId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_RULE_MANAGE);
        return rules.findByIdAndOrganization(context.organizationId(), ruleId)
                .orElseThrow(this::notFound);
    }

    private DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Allocation rule not found",
                "The allocation rule is not available in the current organization.");
    }
}
