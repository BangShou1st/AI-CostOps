package com.aicostops.gateway.request;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Streaming termination transitions after a committed dispatch fence. Client
 * cancel becomes {@code CANCELED_AFTER_DISPATCH} and Provider timeout becomes
 * {@code TIMED_OUT_AFTER_DISPATCH}; the route attempt stays
 * {@code BILLABLE_POSSIBLE} because either outcome may still be billable. No
 * second Provider call is ever issued. All IO runs on the dedicated
 * blocking-DB scheduler, never the event loop.
 */
@Component
public class StreamingLifecycleService {

    private final GatewayRequestMapper requestMapper;
    private final BlockingIoScheduler blockingIo;

    public StreamingLifecycleService(
            GatewayRequestMapper requestMapper, BlockingIoScheduler blockingIo) {
        this.requestMapper = requestMapper;
        this.blockingIo = blockingIo;
    }

    /** Client disconnected after dispatch: request {@code CANCELED_AFTER_DISPATCH}. */
    public Mono<Void> cancelAfterDispatch(long requestId, long orgId) {
        return blockingIo.run(() ->
                requestMapper.markRequestCanceledAfterDispatch(requestId, orgId));
    }

    /** Provider timeout after dispatch: request {@code TIMED_OUT_AFTER_DISPATCH}. */
    public Mono<Void> timeoutAfterDispatch(long requestId, long orgId) {
        return blockingIo.run(() ->
                requestMapper.markRequestTimedOutAfterDispatch(requestId, orgId));
    }
}