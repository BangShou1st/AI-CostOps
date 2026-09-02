package com.aicostops.gateway.config;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Process-local active-stream bound. A acquire is non-blocking so overload is
 * rejected before Provider dispatch instead of queueing unboundedly; the
 * permit is always released on stream complete/error/cancel.
 */
@Component
public class GatewayResourceLimiter {

    private final Semaphore streamPermits;

    public GatewayResourceLimiter(GatewayProperties properties) {
        this.streamPermits = new Semaphore(properties.getMaxActiveStreams(), true);
    }

    /** Non-blocking acquire; false means the configured concurrent-stream bound is exhausted. */
    public boolean tryAcquireStreamPermit() {
        return streamPermits.tryAcquire();
    }

    public void releaseStreamPermit() {
        streamPermits.release();
    }

    public int availableStreamPermits() {
        return streamPermits.availablePermits();
    }
}