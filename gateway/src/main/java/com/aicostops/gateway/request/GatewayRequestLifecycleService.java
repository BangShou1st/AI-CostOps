package com.aicostops.gateway.request;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Durable request/route lifecycle transitions after the dispatch fence. All
 * IO runs on the dedicated blocking-DB scheduler, never the event loop.
 */
@Component
public class GatewayRequestLifecycleService {

    private final GatewayRequestMapper requestMapper;
    private final BlockingIoScheduler blockingIo;

    public GatewayRequestLifecycleService(
            GatewayRequestMapper requestMapper, BlockingIoScheduler blockingIo) {
        this.requestMapper = requestMapper;
        this.blockingIo = blockingIo;
    }

    /** Best-effort transition before Provider I/O: only the request becomes active. */
    public Mono<Void> beginUpstream(long requestId, long orgId, long attemptId) {
        return blockingIo.run(() -> {
            if (requestMapper.findAttemptByIdAndRequest(orgId, requestId, attemptId) == null) {
                throw new IllegalStateException("Route attempt does not belong to the Gateway request");
            }
            requestMapper.markRequestUpstreamActive(requestId, orgId);
        });
    }

    public Mono<Void> markSafe(long requestId, long orgId, long attemptId,
            com.aicostops.gateway.provider.ProviderSafetyReason reason) {
        return blockingIo.run(() -> {
            if (requestMapper.findAttemptByIdAndRequest(orgId, requestId, attemptId) == null
                    || requestMapper.markAttemptSafeForRequest(requestId, attemptId, orgId, reason.name()) != 1) {
                throw new IllegalStateException("Route attempt does not belong to the Gateway request");
            }
        });
    }

    public Mono<Void> markBillablePossible(long requestId, long orgId, long attemptId,
            com.aicostops.gateway.provider.ProviderSafetyReason reason, String providerRequestId) {
        return blockingIo.run(() -> {
            if (requestMapper.findAttemptByIdAndRequest(orgId, requestId, attemptId) == null
                    || requestMapper.markAttemptBillablePossibleWithEvidenceForRequest(
                            requestId, attemptId, orgId, reason.name(), providerRequestId) != 1) {
                throw new IllegalStateException("Route attempt does not belong to the Gateway request");
            }
        });
    }

    /** Successful transport: request TRANSPORT_COMPLETED, route COMPLETED. */
    public Mono<Void> completeSuccess(long requestId, long orgId, long attemptId) {
        return blockingIo.run(() -> {
            requestMapper.markRequestTransportCompleted(requestId, orgId);
            requestMapper.markAttemptCompleted(attemptId, orgId);
        });
    }

    /** Post-dispatch failure: request FAILED_AFTER_DISPATCH; the route stays BILLABLE_POSSIBLE. */
    public Mono<Void> failAfterDispatch(long requestId, long orgId) {
        return blockingIo.run(() -> requestMapper.markRequestFailedAfterDispatch(requestId, orgId));
    }
}
