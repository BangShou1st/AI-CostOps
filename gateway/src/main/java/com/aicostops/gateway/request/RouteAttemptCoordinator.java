package com.aicostops.gateway.request;

import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only route-attempt allocation serialized by the Gateway Request row. */
@Service
public class RouteAttemptCoordinator {

    private final GatewayRequestMapper mapper;
    private final TransactionTemplate transactions;
    private final com.aicostops.gateway.request.RequestIdentityService identity;

    public RouteAttemptCoordinator(GatewayRequestMapper mapper, PlatformTransactionManager manager,
            com.aicostops.gateway.request.RequestIdentityService identity) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.identity = identity;
    }

    public PlannedAttempt plan(long orgId, long requestId, long routingPolicyId,
            String routeReasonCode, long providerAccountId, long providerModelId, long pricingVersionId) {
        var planned = transactions.execute(status -> {
            var request = mapper.findByIdForUpdate(requestId, orgId);
            if (request == null) throw new IllegalStateException("Gateway request is not available");
            if (!Set.of("VALIDATED", "RESERVED", "DISPATCH_INTENT", "UPSTREAM_ACTIVE")
                    .contains(request.state())) {
                throw new PlanRejectedException(PlanRejection.REQUEST_NOT_ROUTEABLE,
                        "The Gateway request is already terminal");
            }
            var previous = mapper.findLatestAttempt(orgId, requestId);
            int attemptNo = previous == null ? 1 : previous.attemptNo() + 1;
            if (attemptNo == 1 && !Set.of("INITIAL_PRIMARY", "INITIAL_FALLBACK").contains(routeReasonCode)) {
                throw new PlanRejectedException(PlanRejection.INVALID_ROUTE_REASON,
                        "The first route must use an initial route reason");
            }
            if (mapper.countHistoricalCandidateAttempts(
                    orgId, requestId, providerAccountId, providerModelId) != 0) {
                throw new PlanRejectedException(PlanRejection.CANDIDATE_ALREADY_ATTEMPTED,
                        "The Provider candidate was already attempted for this request");
            }
            if (attemptNo > 1) {
                // This is the durable N+1 invariant. The caller's ordering is
                // advisory only; every predecessor, and every effective hold
                // belonging to this request, is checked while the request row
                // serializes all route planners.
                if (!"SAFE_FAILOVER".equals(routeReasonCode)
                        || mapper.countNonSafeAttempts(orgId, requestId) != 0) {
                    throw new PlanRejectedException(PlanRejection.PREDECESSOR_NOT_SAFE,
                            "A later route requires every predecessor to be SAFE");
                }
                if (mapper.countEffectiveReservations(orgId, requestId) != 0) {
                    throw new PlanRejectedException(PlanRejection.EFFECTIVE_RESERVATION_REMAINS,
                            "A later route requires no effective reservation for the request");
                }
            }
            var decisionId = identity.newRouteDecisionId();
            try {
                mapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                        orgId, requestId, attemptNo, decisionId, routingPolicyId, providerAccountId,
                        providerModelId, pricingVersionId, routeReasonCode));
            } catch (DuplicateKeyException ex) {
                var winner = mapper.findLatestAttempt(orgId, requestId);
                if (winner == null || winner.attemptNo() != attemptNo) throw ex;
                if (winner.providerAccountId() != providerAccountId
                        || winner.providerModelId() != providerModelId) {
                    throw new PlanRejectedException(PlanRejection.ATTEMPT_RACE,
                            "A different route won the attempt allocation race");
                }
                return new PlannedAttempt(winner.id(), winner.attemptNo(), winner.routeDecisionId());
            }
            var persisted = mapper.findLatestAttempt(orgId, requestId);
            if (persisted == null) throw new IllegalStateException("Route attempt was not persisted");
            mapper.updateCurrentRouteAttempt(requestId, orgId, persisted.id());
            return new PlannedAttempt(persisted.id(), persisted.attemptNo(), persisted.routeDecisionId());
        });
        return Objects.requireNonNull(planned, "Route attempt transaction returned no result");
    }

    public void markSafe(long orgId, long attemptId, ProviderSafetyReason reason) {
        transactions.executeWithoutResult(status -> {
            if (mapper.markAttemptSafe(attemptId, orgId, reason.name()) != 1) {
                throw new IllegalStateException("Route attempt cannot be marked SAFE");
            }
        });
    }

    public void markBillablePossible(long orgId, long attemptId, ProviderSafetyReason reason,
            String providerRequestId) {
        transactions.executeWithoutResult(status -> {
            if (mapper.markAttemptBillablePossibleWithEvidence(attemptId, orgId, reason.name(), providerRequestId) != 1) {
                throw new IllegalStateException("Route attempt cannot be marked BILLABLE_POSSIBLE");
            }
        });
    }

    public record PlannedAttempt(long id, int attemptNo, String routeDecisionId) { }

    public enum PlanRejection {
        CANDIDATE_ALREADY_ATTEMPTED,
        PREDECESSOR_NOT_SAFE,
        EFFECTIVE_RESERVATION_REMAINS,
        ATTEMPT_RACE,
        INVALID_ROUTE_REASON,
        REQUEST_NOT_ROUTEABLE
    }

    public static final class PlanRejectedException extends GatewayErrorException {
        private final PlanRejection rejection;

        public PlanRejectedException(PlanRejection rejection, String message) {
            super(GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS, message);
            this.rejection = rejection;
        }

        public PlanRejection rejection() {
            return rejection;
        }
    }
}
