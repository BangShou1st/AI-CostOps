package com.aicostops.intelligence.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recommendation state commands (M18 V3 round-3 P1).
 *
 * <p>Recommendation mutations change business state and require organization-level BUDGET_MANAGE
 * (the closest frozen manage permission). COST_READ-only users must not fabricate
 * ACK/DISMISS/APPLIED. mark-applied additionally proves same-org ACTIVE routing linkage to the
 * recommended candidate; it only records a human-completed action and never auto-creates routing
 * policy.
 *
 * <p>{@code @Transactional} sits on the public entry points that Spring's bean proxy actually
 * invokes: the conditional status update, the audit append and the returned re-read all commit or
 * roll back together, so a failing audit can never leave a successful status behind. Internal
 * helpers stay private and must not be relied on for their own transactional boundary.
 */
@Service
public class SavingsRecommendationCommandService {

    private final AuthorizationContextService authorizationContexts;
    private final CostFactsMapper facts;
    private final IntelligenceMapper store;
    private final AuditService audit;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public SavingsRecommendationCommandService(AuthorizationContextService authorizationContexts,
            CostFactsMapper facts, IntelligenceMapper store, AuditService audit) {
        this.authorizationContexts = authorizationContexts;
        this.facts = facts;
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public CostIntelligenceReadModels.RecommendationResponse acknowledge(
            AuthenticatedUser user, long id) {
        return transition(user, id, "OPEN", "ACKNOWLEDGED", null,
                "SAVINGS_RECOMMENDATION_ACKNOWLEDGED");
    }

    @Transactional
    public CostIntelligenceReadModels.RecommendationResponse dismiss(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "BUDGET_MANAGE");
        var current = recommendation(context.organizationId(), id);
        if (!"OPEN".equals(current.status()) && !"ACKNOWLEDGED".equals(current.status())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Recommendation state conflict", "Only OPEN or ACKNOWLEDGED can be dismissed.");
        }
        return transition(user, id, current.status(), "DISMISSED", null,
                "SAVINGS_RECOMMENDATION_DISMISSED");
    }

    @Transactional
    public CostIntelligenceReadModels.RecommendationResponse markApplied(
            AuthenticatedUser user, long id, Long routingPolicyId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "BUDGET_MANAGE");
        if (routingPolicyId == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Recommendation validation failed", "Routing policy linkage is required.");
        }
        var current = recommendation(context.organizationId(), id);
        validateAppliedLinkage(context.organizationId(), current, routingPolicyId);
        return transition(user, id, "ACKNOWLEDGED", "APPLIED", routingPolicyId,
                "SAVINGS_RECOMMENDATION_APPLIED");
    }

    private void validateAppliedLinkage(long organizationId,
            IntelligenceMapper.RecommendationRow recommendation, long routingPolicyId) {
        var policy = facts.findRoutingPolicy(routingPolicyId, organizationId);
        if (policy == null || !"ACTIVE".equals(policy.status())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Recommendation validation failed", "Routing policy must be ACTIVE in this organization.");
        }
        if (policy.modelId() != recommendation.logicalModelId()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Recommendation validation failed",
                    "Routing policy logical model must match the recommendation.");
        }
        var candidate = facts.findRoutingCandidate(organizationId, routingPolicyId,
                recommendation.candidateProviderAccountId(), recommendation.candidateProviderModelId());
        if (candidate == null || !"ACTIVE".equals(candidate.status())) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Recommendation validation failed",
                    "Routing policy must actually contain the recommended candidate.");
        }
    }

    private CostIntelligenceReadModels.RecommendationResponse transition(AuthenticatedUser user, long id,
            String from, String to, Long policyId, String auditEvent) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "BUDGET_MANAGE");
        if (store.transitionRecommendation(id, context.organizationId(), from, to, policyId) != 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Recommendation state conflict", "The recommendation is not in a mutable state.");
        }
        audit.append(auditEvent, context.organizationId(), user.userId(),
                "SAVINGS_RECOMMENDATION", id, Map.of("from", from, "to", to));
        return CostIntelligenceReadModels.RecommendationResponse.from(
                recommendation(context.organizationId(), id));
    }

    private IntelligenceMapper.RecommendationRow recommendation(long organizationId, long id) {
        var row = store.findRecommendation(id, organizationId);
        if (row == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Recommendation not found", "The recommendation is not available.");
        }
        return row;
    }
}
