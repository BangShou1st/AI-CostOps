package com.aicostops.gateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded Gateway request telemetry. Labels are always drawn from small fixed
 * enumerations (outcome, error class, provider code) and never from request,
 * trace, credential, org, project, user, provider-request ids or arbitrary
 * client model strings, per AIC-091. Registry lookups are cached per tag set.
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Outcome is a small fixed enum: COMPLETED, FAILED, TIMED_OUT, CANCELED, RATE_LIMITED, REJECTED, DEPENDENCY_UNAVAILABLE. */
    public void recordRequestOutcome(String outcome) {
        registry.counter("gateway_request_total", "outcome", outcome).increment();
    }

    /** errorClass is a small fixed enum: HTTP_ERROR, CONNECT, TIMEOUT; providerCode is a bounded catalog value. */
    public void recordProviderError(String providerCode, String errorClass) {
        registry.counter("gateway_provider_error_total",
                "provider_code", providerCode, "error_class", errorClass).increment();
    }

    public void recordRedisDependencyError() {
        registry.counter("gateway_redis_dependency_error_total").increment();
    }

    /** outcome is a small fixed enum: RELEASED, PENDING_HOLD, SKIPPED, FAILED. */
    public void recordReservationRecovery(String outcome) {
        registry.counter("gateway_reservation_recovery_total", "outcome", outcome).increment();
    }

    /** outcome is a small fixed enum: RESERVED, UNBUDGETED, REJECTED_BUDGET, REJECTED_DEPENDENCY. */
    public void recordReservationAttempt(String outcome) {
        registry.counter("gateway_reservation_attempt_total", "outcome", outcome).increment();
    }

    /** outcome is a small fixed enum: ALLOWED, REJECTED, DEPENDENCY_UNAVAILABLE, DISABLED. */
    public void recordQuota(String outcome) {
        registry.counter("gateway_quota_total", "outcome", outcome).increment();
    }
}