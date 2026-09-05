package com.aicostops.gateway.request;

import com.aicostops.gateway.budget.BudgetReservationService;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.budget.SafeReservationReleaseService;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.observability.GatewayMetrics;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.routing.CandidateEligibilityEvaluator;
import com.aicostops.gateway.routing.DeterministicRouteSelector;
import com.aicostops.gateway.routing.ResolvedRoutingPolicy;
import com.aicostops.gateway.routing.RoutingPolicyResolver;
import com.aicostops.gateway.resilience.CircuitBreakerService;
import com.aicostops.gateway.resilience.RouteCircuitKey;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.aicostops.gateway.request.GatewayRequestService.DispatchResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    private final GatewayMetrics metrics;
    private final BlockingIoScheduler blockingIo;
    private final Clock clock;

    public GatewayRequestOrchestrator(GatewayRequestService requestService,
            RoutingPolicyResolver policyResolver, DeterministicRouteSelector selector,
            CandidateEligibilityEvaluator eligibility, RouteAttemptCoordinator attempts,
            SafeReservationReleaseService releases, BudgetReservationService reservations,
            DispatchFenceService fence, GatewayRequestMapper requestMapper,
            CircuitBreakerService circuits, GatewayMetrics metrics,
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
        this.metrics = metrics;
        this.blockingIo = blockingIo;
        this.clock = clock;
    }

    public Mono<PreparedDispatch> prepareInitial(GatewayRequestService.AuthorizeCommand command,
            boolean streaming) {
        var effectiveCommand = command.streaming() == streaming ? command
                : new GatewayRequestService.AuthorizeCommand(command.principal(), command.logicalModelId(),
                        command.rawBodyBytes(), command.rawIdempotencyKey(), command.effectiveMaxOutputTokens(), streaming);
        return requestService.authorizeForRouting(effectiveCommand)
                .flatMap(authorized -> {
                    var policy = policyResolver.resolve(authorized.principal().organizationId(),
                            authorized.principal().projectId(), authorized.logicalModelId(), Instant.now(clock));
                    var candidates = selector.orderedCandidates(policy);
                    if (candidates.isEmpty()) {
                        return finishInitialNoRoute(authorized, false);
                    }
                    return tryInitialCandidates(authorized, policy, candidates, 0,
                            false, false, false, Set.of(), streaming);
                });
    }

    private Mono<PreparedDispatch> tryInitialCandidates(
            GatewayRequestService.AuthorizedRequest authorized,
            ResolvedRoutingPolicy policy,
            java.util.List<ResolvedRoutingPolicy.Candidate> candidates,
            int index,
            boolean skippedBefore, boolean budgetRejected, boolean safeAttemptProduced,
            Set<ResolvedRoutingPolicy.RouteIdentity> attempted, boolean streaming) {
        if (index >= candidates.size()) {
            return finishInitialNoRoute(authorized, budgetRejected);
        }
        var candidate = candidates.get(index);
        var evaluated = eligibility.evaluate(candidate, policy.logicalModelId(),
                new CandidateEligibilityEvaluator.RequestCapabilities(true, streaming),
                attempted);
        if (!evaluated.eligible()) {
            metrics.recordCandidateRejection(evaluated.reason().name());
            return tryInitialCandidates(authorized, policy, candidates, index + 1,
                    true, budgetRejected, safeAttemptProduced, attempted, streaming);
        }
        return circuits.beforeCall(new RouteCircuitKey(authorized.principal().organizationId(),
                        candidate.providerAccountId(), candidate.providerModelId()))
                .flatMap(decision -> {
                    if (!decision.probeAllowed()) {
                        metrics.recordCandidateRejection("CIRCUIT_OPEN");
                        return tryInitialCandidates(authorized, policy, candidates, index + 1,
                                true, budgetRejected, safeAttemptProduced, attempted, streaming);
                    }
                    if (candidate.pricingVersionId() == null) {
                        return tryInitialCandidates(authorized, policy, candidates, index + 1,
                                true, budgetRejected, safeAttemptProduced, attempted, streaming);
                    }
                    var routeReason = safeAttemptProduced ? "SAFE_FAILOVER"
                            : skippedBefore ? "INITIAL_FALLBACK" : "INITIAL_PRIMARY";
                    return blockingIo.call(() -> {
                        RouteAttemptCoordinator.PlannedAttempt planned;
                        try {
                            planned = attempts.plan(authorized.principal().organizationId(),
                                    authorized.requestId(), policy.id(), routeReason,
                                    candidate.providerAccountId(), candidate.providerModelId(),
                                    candidate.pricingVersionId());
                        } catch (RouteAttemptCoordinator.PlanRejectedException ex) {
                            if (ex.rejection() == RouteAttemptCoordinator.PlanRejection.CANDIDATE_ALREADY_ATTEMPTED) {
                                return InitialCandidateResult.skipped(attempted, false, false);
                            }
                            throw ex;
                        }
                        var nextAttempted = new HashSet<>(attempted);
                        nextAttempted.add(candidate.identity());
                        var admission = reservations.admitSync(new BudgetReservationService.AdmissionCommand(
                                authorized.principal(), authorized.requestId(), planned.id(),
                                authorized.billingPeriodId(), candidate.pricingVersionId(), candidate.currency(),
                                authorized.command().effectiveMaxOutputTokens(), -1L, true));
                        if (admission.outcome() != AdmissionOutcome.RESERVED
                                && admission.outcome() != AdmissionOutcome.UNBUDGETED) {
                            attempts.markSafe(authorized.principal().organizationId(), planned.id(),
                                    budgetSafetyReason(admission.outcome()));
                            return InitialCandidateResult.rejected(nextAttempted,
                                    admission.outcome() == AdmissionOutcome.REJECTED_BUDGET);
                        }
                        fence.commitDispatchFence(authorized.principal().organizationId(),
                                authorized.requestId(), planned.id(), authorized.billingPeriodId(), admission);
                        metrics.recordRoutingDecision(candidate.adapterCode(), routeReason);
                        return InitialCandidateResult.selected(new PreparedDispatch(
                                dispatch(authorized, policy, candidate, planned), authorized.principal(),
                                authorized.command(), policy, Set.copyOf(nextAttempted)));
                    }).flatMap(result -> result.dispatch() != null
                            ? Mono.just(result.dispatch())
                            : tryInitialCandidates(authorized, policy, candidates, index + 1,
                                    true, budgetRejected || result.budgetRejected(),
                                    safeAttemptProduced || result.safeAttemptProduced(),
                                    result.attempted(), streaming));
                });
    }

    private Mono<PreparedDispatch> finishInitialNoRoute(
            GatewayRequestService.AuthorizedRequest authorized, boolean budgetRejected) {
        if (budgetRejected && "REQUIRED".equals(authorized.principal().budgetEnforcementMode())) {
            return blockingIo.run(() -> requestMapper.markRequestRejectedBudget(
                            authorized.requestId(), authorized.principal().organizationId(),
                            authorized.billingPeriodId()))
                    .then(Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED,
                            "Budget is unavailable or insufficient for all eligible routes")));
        }
        return blockingIo.run(() -> requestMapper.markRequestFailedPreDispatch(
                        authorized.requestId(), authorized.principal().organizationId()))
                .then(Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                        "No eligible Provider route is available for the requested model")));
    }

    private static DispatchResult dispatch(GatewayRequestService.AuthorizedRequest authorized,
            ResolvedRoutingPolicy policy, ResolvedRoutingPolicy.Candidate candidate,
            RouteAttemptCoordinator.PlannedAttempt planned) {
        return new GatewayRequestService.DispatchResult(
                authorized.requestId(), authorized.publicRequestId(), planned.id(), planned.routeDecisionId(),
                policy.id(), candidate.providerAccountId(), candidate.providerModelId(),
                candidate.pricingVersionId(), candidate.currency(), candidate.baseUrl(), candidate.adapterCode(),
                candidate.providerModelName(), authorized.logicalModelId(), authorized.maxOutputTokens(),
                authorized.defaultMaxOutputTokens(), authorized.billingPeriodId());
    }

    private record InitialCandidateResult(PreparedDispatch dispatch, boolean budgetRejected,
            boolean safeAttemptProduced, Set<ResolvedRoutingPolicy.RouteIdentity> attempted) {
        private static InitialCandidateResult selected(PreparedDispatch dispatch) {
            return new InitialCandidateResult(dispatch, false, false, dispatch.attempted());
        }

        private static InitialCandidateResult rejected(
                Set<ResolvedRoutingPolicy.RouteIdentity> attempted, boolean budgetRejected) {
            return new InitialCandidateResult(null, budgetRejected, true, Set.copyOf(attempted));
        }

        private static InitialCandidateResult skipped(
                Set<ResolvedRoutingPolicy.RouteIdentity> attempted,
                boolean budgetRejected, boolean safeAttemptProduced) {
            return new InitialCandidateResult(null, budgetRejected, safeAttemptProduced,
                    Set.copyOf(attempted));
        }
    }

    public Mono<PreparedDispatch> prepareNextSafe(PreparedDispatch previous,
            ProviderExecutionException safeFailure) {
        return prepareNextSafe(previous, safeFailure, () -> false, ignored -> { });
    }

    /**
     * Streaming-aware safe advance. The cancellation predicate is checked
     * after SAFE release and again before TX2; the observer runs immediately
     * after TX2 so cancellation finalization can target the newly current route.
     */
    public Mono<PreparedDispatch> prepareNextSafe(PreparedDispatch previous,
            ProviderExecutionException safeFailure, BooleanSupplier cancelled,
            Consumer<PreparedDispatch> onDispatchCommitted) {
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
        }).flatMap(released -> cancelled.getAsBoolean()
                ? convergeSafeTerminal(released).thenReturn(released)
                : findAndPrepareNext(released, cancelled, onDispatchCommitted));
    }

    private Mono<PreparedDispatch> findAndPrepareNext(PreparedDispatch previous,
            BooleanSupplier cancelled, Consumer<PreparedDispatch> onDispatchCommitted) {
        if (cancelled.getAsBoolean()) return Mono.just(previous);
        // The first attempt freezes the policy version. A newly activated
        // policy may affect a later request, never this request's SAFE chain.
        var policy = previous.policy();
        var attempted = new HashSet<>(previous.attempted());
        for (var durable : requestMapper.findAttemptedCandidates(
                previous.principal().organizationId(), previous.requestId())) {
            attempted.add(new ResolvedRoutingPolicy.RouteIdentity(
                    durable.providerAccountId(), durable.providerModelId()));
        }
        var candidates = eligibleCandidates(policy, previous.command().streaming(), attempted);
        if (candidates.isEmpty()) {
            return cancelled.getAsBoolean() ? Mono.just(previous) : finishNoBillableChain(previous);
        }
        return tryCandidates(previous, policy, attempted, candidates, 0,
                cancelled, onDispatchCommitted);
    }

    private Mono<PreparedDispatch> tryCandidates(PreparedDispatch previous,
            ResolvedRoutingPolicy policy, Set<ResolvedRoutingPolicy.RouteIdentity> attempted,
            java.util.List<ResolvedRoutingPolicy.Candidate> candidates, int index,
            BooleanSupplier cancelled, Consumer<PreparedDispatch> onDispatchCommitted) {
        if (cancelled.getAsBoolean()) return Mono.just(previous);
        if (index >= candidates.size()) return finishNoBillableChain(previous);
        var candidate = candidates.get(index);
        return circuits.beforeCall(new RouteCircuitKey(previous.principal().organizationId(),
                        candidate.providerAccountId(), candidate.providerModelId()))
                .flatMap(decision -> {
                    if (!decision.probeAllowed()) {
                        metrics.recordCandidateRejection("CIRCUIT_OPEN");
                        return tryCandidates(previous, policy, attempted, candidates, index + 1,
                                cancelled, onDispatchCommitted);
                    }
                    return blockingIo.call(() -> {
                        if (cancelled.getAsBoolean()) return NextCandidateResult.cancelledResult();
                        var freshCandidate = selector.orderedCandidates(policy).stream()
                                .filter(item -> item.identity().equals(candidate.identity()))
                                .findFirst().map(item -> policyResolver.refreshPricing(
                                        previous.principal().organizationId(), item, Instant.now(clock)))
                                .orElseThrow(() -> new GatewayErrorException(
                                        GatewayErrorCode.GATEWAY_FORBIDDEN, "No eligible Provider route is available"));
                        if (freshCandidate.pricingVersionId() == null) {
                            return NextCandidateResult.skipped();
                        }
                        RouteAttemptCoordinator.PlannedAttempt planned;
                        try {
                            planned = attempts.plan(previous.principal().organizationId(), previous.requestId(),
                                    previous.dispatch().routingPolicyId(), "SAFE_FAILOVER", freshCandidate.providerAccountId(),
                                    freshCandidate.providerModelId(), freshCandidate.pricingVersionId());
                        } catch (RouteAttemptCoordinator.PlanRejectedException ex) {
                            if (ex.rejection() == RouteAttemptCoordinator.PlanRejection.CANDIDATE_ALREADY_ATTEMPTED) {
                                return NextCandidateResult.skipped();
                            }
                            if (ex.rejection() == RouteAttemptCoordinator.PlanRejection.REQUEST_NOT_ROUTEABLE
                                    && cancelled.getAsBoolean()) {
                                return NextCandidateResult.cancelledResult();
                            }
                            throw ex;
                        }
                        var admission = reservations.admitSync(new BudgetReservationService.AdmissionCommand(
                                previous.principal(), previous.requestId(), planned.id(), previous.billingPeriodId(),
                                freshCandidate.pricingVersionId(), freshCandidate.currency(),
                                previous.command().effectiveMaxOutputTokens(), -1L, true));
                        if (admission.outcome() != BudgetReservationService.AdmissionOutcome.RESERVED
                                && admission.outcome() != BudgetReservationService.AdmissionOutcome.UNBUDGETED) {
                            attempts.markSafe(previous.principal().organizationId(), planned.id(),
                                    budgetSafetyReason(admission.outcome()));
                            releases.releaseForSafeAttempt(previous.principal().organizationId(),
                                    previous.requestId(), planned.id(), previous.billingPeriodId());
                            return NextCandidateResult.skipped();
                        }
                        if (cancelled.getAsBoolean()) {
                            attempts.markSafe(previous.principal().organizationId(), planned.id(),
                                    com.aicostops.gateway.provider.ProviderSafetyReason.CLIENT_CANCEL_BEFORE_DISPATCH);
                            releases.releaseForSafeAttempt(previous.principal().organizationId(),
                                    previous.requestId(), planned.id(), previous.billingPeriodId());
                            return NextCandidateResult.cancelledResult();
                        }
                        fence.commitDispatchFence(previous.principal().organizationId(), previous.requestId(),
                                planned.id(), previous.billingPeriodId(), admission);
                        metrics.recordRoutingDecision(freshCandidate.adapterCode(), "SAFE_FAILOVER");
                        var next = prepared(previous, policy, freshCandidate, planned);
                        onDispatchCommitted.accept(next);
                        return NextCandidateResult.selected(next);
                    }).flatMap(next -> next.canceled() || next.dispatch() == null
                            ? (next.canceled() ? Mono.just(previous)
                                    : tryCandidates(previous, policy, attempted, candidates, index + 1,
                                            cancelled, onDispatchCommitted))
                            : Mono.just(next.dispatch()));
                });
    }

    private static ProviderSafetyReason budgetSafetyReason(AdmissionOutcome outcome) {
        return switch (outcome) {
            case REJECTED_BUDGET -> ProviderSafetyReason.BUDGET_INSUFFICIENT_PRE_PROVIDER;
            case REJECTED_DEPENDENCY -> ProviderSafetyReason.BUDGET_BOUND_UNSAFE_PRE_PROVIDER;
            default -> ProviderSafetyReason.BUDGET_NO_MATCH_PRE_PROVIDER;
        };
    }

    /**
     * Converges a canceled all-SAFE chain without weakening the post-dispatch
     * financial path. The mapper's atomic predicates make this a no-op when a
     * current route is still possibly billable or holds an effective reserve.
     */
    public Mono<Void> convergeSafeTerminal(PreparedDispatch prepared) {
        return blockingIo.run(() -> requestMapper.markRequestFailedPreDispatchIfSafeAndReleased(
                prepared.requestId(), prepared.principal().organizationId()));
    }

    private record NextCandidateResult(PreparedDispatch dispatch, boolean canceled) {
        private static NextCandidateResult selected(PreparedDispatch dispatch) {
            return new NextCandidateResult(dispatch, false);
        }

        private static NextCandidateResult skipped() {
            return new NextCandidateResult(null, false);
        }

        private static NextCandidateResult cancelledResult() {
            return new NextCandidateResult(null, true);
        }
    }

    private PreparedDispatch prepared(PreparedDispatch previous, ResolvedRoutingPolicy policy,
            ResolvedRoutingPolicy.Candidate candidate, RouteAttemptCoordinator.PlannedAttempt planned) {
        var result = new GatewayRequestService.DispatchResult(previous.requestId(), previous.publicRequestId(),
                planned.id(), planned.routeDecisionId(), previous.dispatch().routingPolicyId(),
                candidate.providerAccountId(), candidate.providerModelId(), candidate.pricingVersionId(),
                candidate.currency(), candidate.baseUrl(), candidate.adapterCode(), candidate.providerModelName(),
                previous.logicalModelId(), previous.dispatch().maxOutputTokens(),
                previous.dispatch().defaultMaxOutputTokens(), previous.billingPeriodId());
        var attempted = new HashSet<>(previous.attempted());
        attempted.add(candidate.identity());
        return new PreparedDispatch(result, previous.principal(), previous.command(), policy, Set.copyOf(attempted));
    }

    private Mono<PreparedDispatch> finishNoBillableChain(PreparedDispatch previous) {
        metrics.recordFailover("STOPPED", "NO_ELIGIBLE_CANDIDATE");
        return blockingIo.run(() -> requestMapper.markRequestFailedPreDispatch(
                previous.requestId(), previous.principal().organizationId()))
                .then(Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                "No route candidate can be safely dispatched")));
    }

    private List<ResolvedRoutingPolicy.Candidate> eligibleCandidates(
            ResolvedRoutingPolicy policy, boolean streaming,
            Set<ResolvedRoutingPolicy.RouteIdentity> attempted) {
        var candidates = new ArrayList<ResolvedRoutingPolicy.Candidate>();
        for (var candidate : selector.orderedCandidates(policy)) {
            var result = eligibility.evaluate(candidate, policy.logicalModelId(),
                    new CandidateEligibilityEvaluator.RequestCapabilities(true, streaming),
                    attempted);
            if (result.eligible()) {
                candidates.add(candidate);
            } else {
                metrics.recordCandidateRejection(result.reason().name());
            }
        }
        return List.copyOf(candidates);
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
