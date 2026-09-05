package com.aicostops.gateway.observability;

import com.aicostops.gateway.metering.GatewayUsageStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Bounded Gateway request telemetry. Labels are always drawn from small fixed
 * enumerations (outcome, error class, provider code) and never from request,
 * trace, credential, org, project, user, provider-request ids or arbitrary
 * client model strings, per AIC-091. Registry lookups are cached per tag set.
 */
@Component
public class GatewayMetrics {

    private static final Set<String> PROVIDER_CODES = Set.of("MIMO", "OPENAI", "UNKNOWN");
    private static final Set<String> ROUTING_REASONS = Set.of(
            "INITIAL_PRIMARY", "INITIAL_FALLBACK", "SAFE_FAILOVER", "CIRCUIT_OPEN",
            "NO_ELIGIBLE_CANDIDATE", "UNKNOWN");
    private static final Set<String> SAFETY_OUTCOMES = Set.of(
            "SAFE_NO_BILLABLE_EXECUTION", "BILLABLE_POSSIBLE", "UNKNOWN");
    private static final Set<String> CIRCUIT_STATES = Set.of("CLOSED", "OPEN", "HALF_OPEN", "UNKNOWN");
    private static final Set<String> REASON_CODES = Set.of(
            "MISSING_DIMENSION", "NO_USAGE", "MALFORMED_USAGE",
            "POSTDISPATCH_UNCERTAINTY", "DB_FINALIZATION_FAILED",
            "BUDGET_NO_MATCH_PRE_PROVIDER", "BUDGET_INSUFFICIENT_PRE_PROVIDER",
            "BUDGET_BOUND_UNSAFE_PRE_PROVIDER", "CLIENT_CANCEL_BEFORE_DISPATCH",
            "LOCAL_PRE_NETWORK_FAILURE", "DNS_PRE_CONNECT", "CONNECT_REFUSED_PRE_WRITE",
            "CONNECT_TIMEOUT_PRE_WRITE", "TLS_HANDSHAKE_PRE_HTTP_WRITE",
            "HTTP_RESPONSE_RECEIVED", "HEADER_TIMEOUT_WRITE_POSSIBLE", "READ_TIMEOUT",
            "STREAM_TIMEOUT", "CONNECTION_RESET_WRITE_POSSIBLE", "MALFORMED_PROVIDER_RESPONSE",
            "UNKNOWN_POST_DISPATCH", "CREDENTIAL_MISSING", "MODEL_INACTIVE",
            "MODEL_NOT_ROUTING_ELIGIBLE", "ADAPTER_UNAVAILABLE", "CHAT_CAPABILITY_MISMATCH",
            "STREAM_CAPABILITY_MISMATCH", "PRICING_UNAVAILABLE", "ALREADY_ATTEMPTED",
            "CIRCUIT_OPEN", "BUDGET_REJECTED", "NO_ELIGIBLE_CANDIDATE", "UNKNOWN");
    private static final Set<String> REQUEST_OUTCOMES = Set.of(
            "COMPLETED", "FAILED", "TIMED_OUT", "CANCELED", "RATE_LIMITED",
            "REJECTED", "DEPENDENCY_UNAVAILABLE");
    private static final Set<String> PROVIDER_ERROR_CLASSES = Set.of(
            "HTTP_ERROR", "CONNECT", "TIMEOUT");
    private static final Set<String> RESERVATION_RECOVERY_OUTCOMES = Set.of(
            "RELEASED", "PENDING_HOLD", "SKIPPED", "FAILED");
    private static final Set<String> RESERVATION_ATTEMPTS = Set.of(
            "RESERVED", "UNBUDGETED", "REJECTED_BUDGET", "REJECTED_DEPENDENCY");
    private static final Set<String> QUOTA_OUTCOMES = Set.of(
            "ALLOWED", "REJECTED", "DEPENDENCY_UNAVAILABLE", "DISABLED");

    private final MeterRegistry registry;

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Outcome is a small fixed enum: COMPLETED, FAILED, TIMED_OUT, CANCELED, RATE_LIMITED, REJECTED, DEPENDENCY_UNAVAILABLE. */
    public void recordRequestOutcome(String outcome) {
        registry.counter("gateway_request_total", "outcome", bounded(outcome, REQUEST_OUTCOMES))
                .increment();
    }

    /** errorClass is a small fixed enum: HTTP_ERROR, CONNECT, TIMEOUT; providerCode is a bounded catalog value. */
    public void recordProviderError(String providerCode, String errorClass) {
        registry.counter("gateway_provider_error_total",
                "provider_code", boundedProvider(providerCode),
                "error_class", bounded(errorClass, PROVIDER_ERROR_CLASSES)).increment();
    }

    public void recordRedisDependencyError() {
        registry.counter("gateway_redis_dependency_error_total").increment();
    }

    /** outcome is a small fixed enum: RELEASED, PENDING_HOLD, SKIPPED, FAILED. */
    public void recordReservationRecovery(String outcome) {
        registry.counter("gateway_reservation_recovery_total", "outcome",
                bounded(outcome, RESERVATION_RECOVERY_OUTCOMES)).increment();
    }

    /** outcome is a small fixed enum: RESERVED, UNBUDGETED, REJECTED_BUDGET, REJECTED_DEPENDENCY. */
    public void recordReservationAttempt(String outcome) {
        registry.counter("gateway_reservation_attempt_total", "outcome",
                bounded(outcome, RESERVATION_ATTEMPTS)).increment();
    }

    /** outcome is a small fixed enum: ALLOWED, REJECTED, DEPENDENCY_UNAVAILABLE, DISABLED. */
    public void recordQuota(String outcome) {
        registry.counter("gateway_quota_total", "outcome", bounded(outcome, QUOTA_OUTCOMES))
                .increment();
    }

    /** Metering status is a fixed enum and the only label on this counter. */
    public void recordUsageStatus(GatewayUsageStatus status) {
        var bounded = status == null ? "UNKNOWN" : status.name();
        registry.counter("gateway_usage_total", "status", bounded).increment();
    }

    /** Incomplete usage uses only bounded Provider/reason catalog values. */
    public void recordMeteringIncomplete(String providerCode, String reasonCode) {
        registry.counter("gateway_metering_incomplete_total",
                "provider_code", boundedProvider(providerCode),
                "reason_code", boundedReason(reasonCode)).increment();
    }

    /** Unknown usage uses only bounded Provider/reason catalog values. */
    public void recordMeteringUnknown(String providerCode, String reasonCode) {
        registry.counter("gateway_metering_unknown_total",
                "provider_code", boundedProvider(providerCode),
                "reason_code", boundedReason(reasonCode)).increment();
    }

    public void recordProviderUsageParseError(String providerCode) {
        registry.counter("gateway_provider_usage_parse_error_total",
                "provider_code", boundedProvider(providerCode)).increment();
    }

    public void recordRoutingDecision(String adapterCode, String reason) {
        registry.counter("gateway_routing_decision_total",
                "adapter_code", boundedProvider(adapterCode),
                "reason", bounded(reason, ROUTING_REASONS)).increment();
    }

    public void recordCandidateRejection(String reason) {
        registry.counter("gateway_candidate_rejection_total",
                "reason", boundedReason(reason)).increment();
    }

    public void recordProviderSafety(String adapterCode, String outcome, String reason) {
        registry.counter("gateway_provider_safety_total",
                "adapter_code", boundedProvider(adapterCode),
                "outcome", bounded(outcome, SAFETY_OUTCOMES),
                "reason", boundedReason(reason)).increment();
    }

    public void recordFailover(String outcome, String reason) {
        registry.counter("gateway_failover_total",
                "outcome", bounded(outcome, Set.of("ADVANCED", "STOPPED", "UNKNOWN")),
                "reason", boundedReason(reason)).increment();
    }

    public void recordCircuitTransition(String adapterCode, String from, String to, String reason) {
        registry.counter("gateway_circuit_transition_total",
                "adapter_code", boundedProvider(adapterCode),
                "from", bounded(from, CIRCUIT_STATES),
                "to", bounded(to, CIRCUIT_STATES),
                "reason", boundedReason(reason)).increment();
    }

    public void recordCircuitRedisError(String operation) {
        registry.counter("gateway_circuit_redis_error_total",
                "operation", bounded(operation, Set.of("BEFORE_CALL", "RECORD_SUCCESS",
                        "RECORD_FAILURE", "UNKNOWN"))).increment();
    }

    private static String boundedProvider(String providerCode) {
        return PROVIDER_CODES.contains(providerCode) ? providerCode : "UNKNOWN";
    }

    private static String boundedReason(String reasonCode) {
        return REASON_CODES.contains(reasonCode) ? reasonCode : "UNKNOWN";
    }

    private static String bounded(String value, Set<String> allowed) {
        return allowed.contains(value) ? value : "UNKNOWN";
    }
}
