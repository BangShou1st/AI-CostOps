package com.aicostops.gateway.metering;

import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.persistence.GatewayUsageMapper;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes one immutable usage observation and the matching Gateway terminal
 * state in one short local transaction. Provider I/O is never performed here.
 */
@Service
public class GatewayUsageFinalizationService {

    public static final int MAX_SAFE_METADATA_BYTES = 8 * 1024;

    private static final Set<String> SAFE_METADATA_KEYS = Set.of("provider_completion_id");

    private final GatewayUsageMapper usageMapper;
    private final GatewayRequestMapper requestMapper;
    private final BudgetReservationMapper reservationMapper;
    private final GatewayUsageClassifier classifier;
    private final TransactionTemplate transactions;
    private final BlockingIoScheduler blockingIo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GatewayUsageFinalizationService(
            GatewayUsageMapper usageMapper,
            GatewayRequestMapper requestMapper,
            BudgetReservationMapper reservationMapper,
            GatewayUsageClassifier classifier,
            PlatformTransactionManager transactionManager,
            BlockingIoScheduler blockingIo,
            ObjectMapper objectMapper,
            Clock clock) {
        this.usageMapper = usageMapper;
        this.requestMapper = requestMapper;
        this.reservationMapper = reservationMapper;
        this.classifier = classifier;
        this.transactions = new TransactionTemplate(transactionManager);
        this.blockingIo = blockingIo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Reactive entry point; the whole blocking transaction runs on gateway-db. */
    public Mono<FinalizationResult> finalizeSuccess(
            long requestId, long orgId, long routeAttemptId,
            GatewayUsageObservation observation) {
        return blockingIo.call(() -> transactions.execute(status ->
                finalizeBlocking(requestId, orgId, routeAttemptId, observation)));
    }

    /**
     * Persists best available post-dispatch evidence and transport failure in
     * one transaction. A bound ACTIVE reservation becomes PENDING_HOLD; it is
     * never released because transport failure does not prove zero cost.
     */
    public Mono<FinalizationResult> finalizeFailure(
            long requestId, long orgId, long routeAttemptId,
            GatewayUsageObservation observation, TransportFailure failure) {
        return blockingIo.call(() -> transactions.execute(status ->
                finalizeFailureBlocking(requestId, orgId, routeAttemptId, observation, failure)));
    }

    private FinalizationResult finalizeBlocking(
            long requestId, long orgId, long routeAttemptId,
            GatewayUsageObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("Usage observation is required");
        }
        var lineage = usageMapper.lockLineage(orgId, requestId, routeAttemptId);
        if (lineage == null) {
            throw new IllegalStateException("Gateway request route lineage is unavailable");
        }

        GatewayUsageMapper.FactRow current = null;
        if (lineage.currentUsageFactId() != null) {
            current = usageMapper.findFact(orgId, lineage.currentUsageFactId());
            if (current == null) {
                throw new IllegalStateException("Gateway current usage fact is unavailable");
            }
            // Realtime Gateway never competes with an already published FINAL.
            if (GatewayUsageStatus.FINAL.name().equals(current.status())) {
                return new FinalizationResult(
                        GatewayUsageStatus.FINAL, current.id(), false);
            }
        }

        var classification = classifier.classify(
                usageMapper.findPricingDimensions(orgId, lineage.pricingVersionId()), observation);
        var effectiveTime = effectiveTime(observation, lineage.dispatchIntentAt());
        var observedAt = Instant.now(clock);
        var safeMetadata = safeMetadataJson(observation.safeProviderMetadata());
        var factInsert = new GatewayUsageMapper.UsageFactInsert(
                orgId,
                requestId,
                routeAttemptId,
                current == null ? 1 : current.sequence() + 1,
                classification.status().name(),
                current == null ? null : current.id(),
                safeProviderRequestId(observation.providerRequestId()),
                effectiveTime.value(),
                effectiveTime.source(),
                lineage.pricingVersionId(),
                lineage.currency(),
                safeMetadata,
                observedAt,
                observedAt);
        requireOne(usageMapper.insertFact(factInsert), "usage fact insert");
        var factId = usageMapper.lastInsertId();
        for (var dimension : classification.dimensions()) {
            requireOne(usageMapper.insertDimension(new GatewayUsageMapper.DimensionInsert(
                    orgId, factId, dimension.dimensionCode(), dimension.quantity(),
                    dimension.provenance())), "usage dimension insert");
        }
        requireOne(usageMapper.updateCurrentUsageFact(orgId, requestId, factId),
                "current usage fact update");

        beforeLifecycleUpdate();
        convergeSuccessfulLifecycle(requestId, orgId, routeAttemptId, lineage);
        return new FinalizationResult(classification.status(), factId, true);
    }

    private FinalizationResult finalizeFailureBlocking(
            long requestId, long orgId, long routeAttemptId,
            GatewayUsageObservation observation, TransportFailure failure) {
        if (observation == null || failure == null) {
            throw new IllegalArgumentException("Failure evidence and terminal state are required");
        }
        var lineage = usageMapper.lockLineage(orgId, requestId, routeAttemptId);
        if (lineage == null) {
            throw new IllegalStateException("Gateway request route lineage is unavailable");
        }
        var current = lineage.currentUsageFactId() == null
                ? null : usageMapper.findFact(orgId, lineage.currentUsageFactId());
        long factId = current == null ? 0L : current.id();
        GatewayUsageStatus usageStatus;
        if (current != null && GatewayUsageStatus.FINAL.name().equals(current.status())) {
            usageStatus = GatewayUsageStatus.FINAL;
        } else {
            var classification = classifier.classify(
                    usageMapper.findPricingDimensions(orgId, lineage.pricingVersionId()), observation);
            var effectiveTime = effectiveTime(observation, lineage.dispatchIntentAt());
            var observedAt = Instant.now(clock);
            var factInsert = new GatewayUsageMapper.UsageFactInsert(
                    orgId, requestId, routeAttemptId,
                    current == null ? 1 : current.sequence() + 1,
                    classification.status().name(),
                    current == null ? null : current.id(),
                    safeProviderRequestId(observation.providerRequestId()),
                    effectiveTime.value(), effectiveTime.source(), lineage.pricingVersionId(),
                    lineage.currency(), safeMetadataJson(observation.safeProviderMetadata()),
                    observedAt, observedAt);
            requireOne(usageMapper.insertFact(factInsert), "usage fact insert");
            factId = usageMapper.lastInsertId();
            for (var dimension : classification.dimensions()) {
                requireOne(usageMapper.insertDimension(new GatewayUsageMapper.DimensionInsert(
                        orgId, factId, dimension.dimensionCode(), dimension.quantity(),
                        dimension.provenance())), "usage dimension insert");
            }
            requireOne(usageMapper.updateCurrentUsageFact(orgId, requestId, factId),
                    "current usage fact update");
            usageStatus = classification.status();
        }

        var reservation = reservationMapper.lockReservationByRouteAttempt(orgId, routeAttemptId);
        if (reservation != null && "ACTIVE".equals(reservation.status())) {
            requireOne(reservationMapper.holdActiveReservation(
                    reservation.id(), orgId, reservation.version()), "reservation pending hold");
        }
        convergeFailureLifecycle(requestId, orgId, routeAttemptId, lineage, failure);
        return new FinalizationResult(usageStatus, factId, current == null
                || !GatewayUsageStatus.FINAL.name().equals(current.status()));
    }

    /** Test seam for verifying that the local transaction rolls back as one unit. */
    protected void beforeLifecycleUpdate() {
        // Production path intentionally has no work here.
    }

    private void convergeSuccessfulLifecycle(
            long requestId, long orgId, long routeAttemptId,
            GatewayUsageMapper.LineageRow lineage) {
        if ("UPSTREAM_ACTIVE".equals(lineage.requestState())
                || "DISPATCH_INTENT".equals(lineage.requestState())) {
            requireOne(requestMapper.markRequestTransportCompleted(requestId, orgId),
                    "request transport completion");
        }
        if ("BILLABLE_POSSIBLE".equals(lineage.routeStatus())
                || "DISPATCH_INTENT".equals(lineage.routeStatus())) {
            requireOne(requestMapper.markAttemptCompleted(routeAttemptId, orgId),
                    "route completion");
        }
    }

    private void convergeFailureLifecycle(
            long requestId, long orgId, long routeAttemptId,
            GatewayUsageMapper.LineageRow lineage, TransportFailure failure) {
        if ("DISPATCH_INTENT".equals(lineage.routeStatus())) {
            requireOne(requestMapper.markAttemptBillablePossible(routeAttemptId, orgId),
                    "route billable-possible transition");
        }
        if ("DISPATCH_INTENT".equals(lineage.requestState())
                || "UPSTREAM_ACTIVE".equals(lineage.requestState())) {
            var updated = switch (failure) {
                case FAILED -> requestMapper.markRequestFailedAfterDispatch(requestId, orgId);
                case CANCELED -> requestMapper.markRequestCanceledAfterDispatch(requestId, orgId);
                case TIMED_OUT -> requestMapper.markRequestTimedOutAfterDispatch(requestId, orgId);
            };
            requireOne(updated, "request post-dispatch failure");
        }
    }

    private EffectiveTime effectiveTime(GatewayUsageObservation observation, Instant dispatchIntentAt) {
        if (observation.providerBillingTimestamp() != null) {
            return new EffectiveTime(observation.providerBillingTimestamp(),
                    "PROVIDER_BILLING_TIMESTAMP");
        }
        if (observation.providerRequestTimestamp() != null) {
            return new EffectiveTime(observation.providerRequestTimestamp(),
                    "PROVIDER_REQUEST_TIMESTAMP");
        }
        if (dispatchIntentAt != null) {
            return new EffectiveTime(dispatchIntentAt,
                    "GATEWAY_DISPATCH_INTENT_TIMESTAMP");
        }
        throw new IllegalStateException("Durable dispatch intent timestamp is required");
    }

    private String safeProviderRequestId(String providerRequestId) {
        if (providerRequestId == null || providerRequestId.isBlank()
                || providerRequestId.length() > 255) {
            return null;
        }
        return providerRequestId;
    }

    private String safeMetadataJson(Map<String, String> metadata) {
        var allowed = new LinkedHashMap<String, String>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (SAFE_METADATA_KEYS.contains(key) && value != null
                        && value.length() <= 255 && value.matches("[A-Za-z0-9._:-]+")) {
                    allowed.put(key, value);
                }
            });
        }
        if (allowed.isEmpty()) {
            return null;
        }
        try {
            var json = objectMapper.writeValueAsString(allowed);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_SAFE_METADATA_BYTES) {
                throw new IllegalArgumentException("Safe Provider metadata exceeds 8 KiB");
            }
            return json;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Safe Provider metadata serialization failed", ex);
        }
    }

    private static void requireOne(int updated, String operation) {
        if (updated != 1) {
            throw new IllegalStateException("Expected one row for " + operation + ", got " + updated);
        }
    }

    public record FinalizationResult(
            GatewayUsageStatus status,
            long usageFactId,
            boolean newlyPublished) {
    }

    public enum TransportFailure {
        FAILED,
        CANCELED,
        TIMED_OUT
    }

    private record EffectiveTime(Instant value, String source) {
    }
}
