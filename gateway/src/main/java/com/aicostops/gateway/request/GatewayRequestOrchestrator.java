package com.aicostops.gateway.request;

import com.aicostops.gateway.budget.BudgetReservationService;
import com.aicostops.gateway.budget.SafeReservationReleaseService;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.routing.CandidateEligibilityEvaluator;
import com.aicostops.gateway.routing.DeterministicRouteSelector;
import com.aicostops.gateway.routing.ResolvedRoutingPolicy;
import com.aicostops.gateway.routing.RoutingPolicyResolver;
import com.aicostops.gateway.resilience.CircuitBreakerService;
import com.aicostops.gateway.resilience.RouteCircuitKey;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Owns deterministic preparation and the only legal SAFE failover path. */
@Service
public class GatewayRequestOrchestrator {

    private final GatewayRequestService requestService;
    private final RoutingPolicyResolver policyResolver;
    private final DeterministicRouteSelector selector;
    private final CandidateEligibilityEvaluator eligibility;
    private final RouteAttemptCoordinator attempts;
    private final SafeReservationReleaseService releases;
    private final BudgetReservationService reservations;
    private final DispatchFenceService fence;
    private final GatewayRequestMapper requestMapper;
    private final CircuitBreakerService circuits;
    private final BlockingIoScheduler blockingIo;
    private final Clock clock;

    public GatewayRequestOrchestrator(GatewayRequestService requestService,
            RoutingPolicyResolver policyResolver, DeterministicRouteSelector selector,
            CandidateEligibilityEvaluator eligibility, RouteAttemptCoordinator attempts,
            SafeReservationReleaseService releases, BudgetReservationService reservations,
            DispatchFenceService fence, GatewayRequestMapper requestMapper,
            CircuitBreakerService circuits,
            BlockingIoScheduler blockingIo, Clock clock) {
        this.requestService = requestService;
        this.policyResolver = policyResolver;
        this.selector = selector;
        this.eligibility = eligibility;
        this.attempts = attempts;
        this.releases = releases;
        this.reservations = reservations;
        this.fence = fence;
        this.requestMapper = requestMapper;
        this.circuits = circuits;
        this.blockingIo = blockingIo;
        this.clock = clock;
    }

    public Mono<PreparedDispatch> prepareInitial(GatewayRequestService.AuthorizeCommand command,
            boolean streaming) {
        var effectiveCommand = command.streaming() == streaming ? command
                : new GatewayRequestService.AuthorizeCommand(command.principal(), command.logicalModelId(),
                        command.rawBodyBytes(), command.rawIdempotencyKey(), command.effectiveMaxOutputTokens(), streaming);
        return requestService.authorizeAndFence(effectiveCommand)
                .map(result -> {
                    var policy = policyResolver.resolve(effectiveCommand.principal().organizationId(),
                            effectiveCommand.principal().projectId(), result.logicalModelId(), Instant.now(clock));
                    var attempted = new HashSet<ResolvedRoutingPolicy.RouteIdentity>();
                    policy.candidates().stream()
                            .filter(candidate -> candidate.providerAccountId() == result.providerAccountId()
                                    && candidate.providerModelId() == result.providerModelId())
                            .findFirst().ifPresent(candidate -> attempted.add(candidate.identity()));
                    return new PreparedDispatch(result, effectiveCommand.principal(), effectiveCommand,
                            policy, Set.copyOf(attempted));
                });
    }

    public Mono<PreparedDispatch> prepareNextSafe(PreparedDispatch previous,
            ProviderExecutionException safeFailure) {
        if (safeFailure == null || safeFailure.safetyOutcome() != ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION) {
            return Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS,
                    "Only positively safe Provider evidence can advance routing"));
        }
        return blockingIo.call(() -> {
            attempts.markSafe(previous.principal().organizationId(), previous.routeAttemptId(), safeFailure.safetyReason());
            var release = releases.releaseForSafeAttempt(previous.principal().organizationId(),
                    previous.requestId(), previous.routeAttemptId(), previous.billingPeriodId());
            if (release.status() == SafeReservationReleaseService.ReleaseStatus.PENDING_HOLD
                    || release.status() == SafeReservationReleaseService.ReleaseStatus.SKIPPED) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "The safe route reservation could not be released");
            }
            return previous;
        }).flatMap(this::findAndPrepareNext);
    }

    private Mono<PreparedDispatch> findAndPrepareNext(PreparedDispatch previous) {
        var now = Instant.now(clock);
        var policy = policyResolver.resolve(previous.principal().organizationId(),
                previous.principal().projectId(), previous.logicalModelId(), now);
        var attempted = new HashSet<>(previous.attempted());
        var candidates = selector.orderedCandidates(policy).stream()
                .filter(candidate -> !attempted.contains(candidate.identity()))
                .filter(candidate -> eligibility.evaluate(candidate,
                        new CandidateEligibilityEvaluator.RequestCapabilities(true, previous.command().streaming()),
                        attempted, now).eligible())
                .toList();
        if (candidates.isEmpty()) return finishNoBillableChain(previous);
        return tryCandidates(previous, policy, attempted, candidates, 0);
    }

    private Mono<PreparedDispatch> tryCandidates(PreparedDispatch previous,
            ResolvedRoutingPolicy policy, Set<ResolvedRoutingPolicy.RouteIdentity> attempted,
            java.util.List<ResolvedRoutingPolicy.Candidate> candidates, int index) {
        if (index >= candidates.size()) return finishNoBillableChain(previous);
        var candidate = candidates.get(index);
        return circuits.beforeCall(new RouteCircuitKey(previous.principal().organizationId(),
                        candidate.providerAccountId(), candidate.providerModelId()))
                .flatMap(decision -> {
                    if (!decision.probeAllowed()) return tryCandidates(previous, policy, attempted, candidates, index + 1);
                    return blockingIo.call(() -> {
                        var freshPolicy = policyResolver.resolve(previous.principal().organizationId(),
                                previous.principal().projectId(), previous.logicalModelId(), Instant.now(clock));
                        var freshCandidate = selector.orderedCandidates(freshPolicy).stream()
                                .filter(item -> item.identity().equals(candidate.identity()))
                                .findFirst().orElseThrow(() -> new GatewayErrorException(
                                        GatewayErrorCode.GATEWAY_FORBIDDEN, "No eligible Provider route is available"));
                        if (freshCandidate.pricingVersionId() == null) {
                            return null;
                        }
                        var planned = attempts.plan(previous.principal().organizationId(), previous.requestId(),
                                freshPolicy.id(), "SAFE_FAILOVER", freshCandidate.providerAccountId(),
                                freshCandidate.providerModelId(), freshCandidate.pricingVersionId());
                        var admission = reservations.admitSync(new BudgetReservationService.AdmissionCommand(
                                previous.principal(), previous.requestId(), planned.id(), previous.billingPeriodId(),
                                freshCandidate.pricingVersionId(), freshCandidate.currency(),
                                previous.command().effectiveMaxOutputTokens(), -1L));
                        if (admission.outcome() != BudgetReservationService.AdmissionOutcome.RESERVED
                                && admission.outcome() != BudgetReservationService.AdmissionOutcome.UNBUDGETED) {
                            attempts.markSafe(previous.principal().organizationId(), planned.id(),
                                    com.aicostops.gateway.provider.ProviderSafetyReason.LOCAL_PRE_NETWORK_FAILURE);
                            return null;
                        }
                        fence.commitDispatchFence(previous.principal().organizationId(), previous.requestId(),
                                planned.id(), previous.billingPeriodId(), admission);
                        return prepared(previous, freshPolicy, freshCandidate, planned);
                    }).flatMap(next -> next == null
                            ? tryCandidates(previous, policy, attempted, candidates, index + 1)
                            : Mono.just(next));
                });
    }

    private PreparedDispatch prepared(PreparedDispatch previous, ResolvedRoutingPolicy policy,
            ResolvedRoutingPolicy.Candidate candidate, RouteAttemptCoordinator.PlannedAttempt planned) {
        var result = new GatewayRequestService.DispatchResult(previous.requestId(), previous.publicRequestId(),
                planned.id(), planned.routeDecisionId(), policy.id(), candidate.providerAccountId(),
                candidate.providerModelId(), candidate.pricingVersionId(), candidate.currency(), candidate.baseUrl(),
                candidate.adapterCode(), candidate.providerModelName(), previous.logicalModelId(),
                previous.dispatch().maxOutputTokens(), previous.dispatch().defaultMaxOutputTokens(), previous.billingPeriodId());
        var attempted = new HashSet<>(previous.attempted());
        attempted.add(candidate.identity());
        return new PreparedDispatch(result, previous.principal(), previous.command(), policy, Set.copyOf(attempted));
    }

    private Mono<PreparedDispatch> finishNoBillableChain(PreparedDispatch previous) {
        return blockingIo.run(() -> requestMapper.markRequestFailedPreDispatch(
                previous.requestId(), previous.principal().organizationId()))
                .then(Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                        "No route candidate can be safely dispatched")));
    }

    public record PreparedDispatch(GatewayRequestService.DispatchResult dispatch,
            com.aicostops.gateway.auth.GatewayPrincipal principal,
            GatewayRequestService.AuthorizeCommand command,
            ResolvedRoutingPolicy policy,
            Set<ResolvedRoutingPolicy.RouteIdentity> attempted) {
        public long requestId() { return dispatch.requestId(); }
        public String publicRequestId() { return dispatch.publicRequestId(); }
        public long routeAttemptId() { return dispatch.routeAttemptId(); }
        public long billingPeriodId() { return dispatch.billingPeriodId(); }
        public long logicalModelId() { return dispatch.logicalModelId(); }
    }
}
