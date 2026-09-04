package com.aicostops.gateway.request;

import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import java.util.Objects;
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
            var previous = mapper.findLatestAttempt(orgId, requestId);
            int attemptNo = previous == null ? 1 : previous.attemptNo() + 1;
            if (previous != null && (!"SAFE_NO_BILLABLE_EXECUTION".equals(previous.status())
                    || mapper.countNonSafeAttempts(orgId, requestId) != 0)) {
                throw new IllegalStateException("A later route attempt requires all predecessors to be SAFE");
            }
            var decisionId = identity.newRouteDecisionId();
            try {
                mapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                        orgId, requestId, attemptNo, decisionId, routingPolicyId, providerAccountId,
                        providerModelId, pricingVersionId, routeReasonCode));
            } catch (DuplicateKeyException ex) {
                var winner = mapper.findLatestAttempt(orgId, requestId);
                if (winner == null || winner.attemptNo() != attemptNo) throw ex;
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
}
