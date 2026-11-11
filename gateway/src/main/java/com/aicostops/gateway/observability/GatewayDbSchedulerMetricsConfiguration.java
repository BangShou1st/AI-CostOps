package com.aicostops.gateway.observability;

import com.aicostops.gateway.config.GatewayBlockingIoScheduler;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes bounded blocking-DB scheduler saturation gauges. Instrumentation
 * only: the scheduler's scheduling behavior is unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayDbSchedulerMetricsConfiguration {

    public GatewayDbSchedulerMetricsConfiguration(
            GatewayMetrics metrics, GatewayBlockingIoScheduler scheduler) {
        metrics.bindDbScheduler(scheduler);
    }
}
