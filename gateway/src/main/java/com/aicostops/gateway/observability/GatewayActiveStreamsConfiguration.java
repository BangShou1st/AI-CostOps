package com.aicostops.gateway.observability;

import com.aicostops.gateway.config.GatewayResourceLimiter;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the process-local active-stream gauge at startup. The gauge reads the
 * stream-permit semaphore indirectly through the limiter, so overload
 * saturation is visible in Prometheus without any per-request label.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayActiveStreamsConfiguration {

    private final GatewayMetrics metrics;
    private final GatewayResourceLimiter limiter;

    public GatewayActiveStreamsConfiguration(GatewayMetrics metrics,
            GatewayResourceLimiter limiter) {
        this.metrics = metrics;
        this.limiter = limiter;
    }

    @PostConstruct
    void bindActiveStreamsGauge() {
        metrics.bindActiveStreams(limiter);
    }
}
