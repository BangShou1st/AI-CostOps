package com.aicostops.gateway.request;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult;
import com.aicostops.gateway.cache.GatewayConfigCache;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.routing.CandidateEligibilityEvaluator;
import com.aicostops.gateway.routing.DeterministicRouteSelector;
import com.aicostops.gateway.routing.ResolvedRoutingPolicy;
import com.aicostops.gateway.routing.RoutingPolicyResolver;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Request-time authorization and durable Gateway request creation.
 *
 * <p>Before any Provider I/O: validate the model/commercial context
 * (explicit credential-model allowlist, active model catalog, eligible single
 * M11 Provider route), converge or create the {@code VALIDATED} request,
 * create route attempt 1 {@code PLANNED}, run M12 TX1 MySQL-authoritative
 * budget admission, then commit the BillingPeriod dispatch fence (TX2). The
 * same idempotency identity never authorizes a second Provider dispatch.
 */
@Service
public class GatewayRequestService {

    private static final long IDEMPOTENCY_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(80);

    private final GatewayReadMapper readMapper;
    private final GatewayRequestMapper requestMapper;
    private final RequestIdentityService identityService;
    private final DispatchFenceService dispatchFenceService;
    private final BudgetReservationService reservationService;
    private final BlockingIoScheduler blockingIo;
    private final Clock clock;
    private final RoutingPolicyResolver routingPolicyResolver;
    private final DeterministicRouteSelector routeSelector;
    private final CandidateEligibilityEvaluator eligibilityEvaluator;
    private final GatewayConfigCache configCache;
    private final GatewayIdempotencyHint idempotencyHint;

    public GatewayRequestService(
            GatewayReadMapper readMapper,
            GatewayRequestMapper requestMapper,
            RequestIdentityService identityService,
            DispatchFenceService dispatchFenceService,
            BudgetReservationService reservationService,
            BlockingIoScheduler blockingIo,
            Clock clock,
            RoutingPolicyResolver routingPolicyResolver,
            DeterministicRouteSelector routeSelector,
            CandidateEligibilityEvaluator eligibilityEvaluator) {
        this(readMapper, requestMapper, identityService, dispatchFenceService, reservationService,
                blockingIo, clock, routingPolicyResolver, routeSelector, eligibilityEvaluator, null, null);
    }

    @Autowired
    public GatewayRequestService(
            GatewayReadMapper readMapper,
            GatewayRequestMapper requestMapper,
            RequestIdentityService identityService,
            DispatchFenceService dispatchFenceService,
            BudgetReservationService reservationService,
            BlockingIoScheduler blockingIo,
            Clock clock,
            RoutingPolicyResolver routingPolicyResolver,
            DeterministicRouteSelector routeSelector,
            CandidateEligibilityEvaluator eligibilityEvaluator,
            GatewayConfigCache configCache,
            GatewayIdempotencyHint idempotencyHint) {
        this.readMapper = readMapper;
        this.requestMapper = requestMapper;
        this.identityService = identityService;
        this.dispatchFenceService = dispatchFenceService;
        this.reservationService = reservationService;
        this.blockingIo = blockingIo;
        this.clock = clock;
        this.routingPolicyResolver = routingPolicyResolver;
        this.routeSelector = routeSelector;
        this.eligibilityEvaluator = eligibilityEvaluator;
        this.configCache = configCache;
        this.idempotencyHint = idempotencyHint;
    }

    /**
     * Test-only legacy single-candidate path.
     *
     * <p><strong>Production must not call this method.</strong> The M14
     * {@link GatewayRequestOrchestrator#prepareInitial} owns candidate ordering,
     * circuit filtering, per-candidate admission and TX2. This method is kept
     * only for the three integration tests that exercise the dispatch fence,
     * idempotency and reservation recovery against a single deterministic
     * candidate; any dispatch or reservation semantic change belongs in the
     * orchestrator, not here.
     */
    public Mono<DispatchResult> authorizeAndFence(AuthorizeCommand command) {
        return blockingIo.call(() -> authorizeAndFenceBlocking(command));
    }

    /**
     * Validates the request identity and catalog context without selecting a
     * Provider route. The M14 orchestrator owns candidate ordering, circuit
     * filtering, per-candidate admission and TX2.
     */
    public Mono<AuthorizedRequest> authorizeForRouting(AuthorizeCommand command) {
        return blockingIo.call(() -> authorizeForRoutingBlocking(command));
    }

    private AuthorizedRequest authorizeForRoutingBlocking(AuthorizeCommand command) {
        return authorizePrelude(command);
    }

    /**
     * Route-independent authorization prelude shared by the production routing
     * path and the test-only single-candidate fence path: idempotency identity,
     * credential-model allowlist, ACTIVE model catalog, OPEN BillingPeriod
     * fence and VALIDATED request convergence.
     */
    private AuthorizedRequest authorizePrelude(AuthorizeCommand command) {
        var now = Instant.now(clock);
        var principal = command.principal();

        identityService.validateIdempotencyKey(command.rawIdempotencyKey());
        var idemDigest = identityService.idempotencyKeyDigest(command.rawIdempotencyKey());
        var fingerprint = identityService.requestFingerprint(command.rawBodyBytes());

        // 1. Explicit credential-model allowlist (deny by default) + active model.
        boolean modelAllowed = configCache != null
                ? configCache.getPrincipalState(principal).allowsModel(command.logicalModelId())
                : readMapper.findActiveModelIds(principal.credentialId()).contains(command.logicalModelId());
        if (!modelAllowed) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "The requested model is not allowed for this credential");
        }
        var model = command.preResolvedModel();
        if (model == null || model.id() != command.logicalModelId()) {
            model = configCache != null
                    ? configCache.getModelById(principal.organizationId(), command.logicalModelId())
                    : readMapper.findModelById(command.logicalModelId());
        }
        if (model == null || !"ACTIVE".equals(model.status())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "The requested model is not active");
        }

        // 2. Resolve the OPEN BillingPeriod financial fence for dispatch time.
        var periodId = readMapper.findOpenBillingPeriodId(principal.organizationId(), now);
        if (periodId == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "No open billing period is available for dispatch");
        }

        // 3. Converge or create the durable VALIDATED request.
        var request = convergeExistingOrCreate(principal, command, idemDigest, fingerprint);
        return new AuthorizedRequest(request.requestId(), request.publicRequestId(),
                principal, command, model.id(), model.modelKey(), model.maxOutputTokens(),
                model.defaultMaxOutputTokens(), periodId);
    }

    private DispatchResult authorizeAndFenceBlocking(AuthorizeCommand command) {
        var authorized = authorizePrelude(command);
        var principal = authorized.principal();
        var now = Instant.now(clock);

        // 4. Resolve the exact project policy first; only when it is absent do
        // we use the organization-default policy. Candidate order is frozen
        // by (priority ASC, candidate id ASC), and a candidate is rejected
        // before any reservation or Provider I/O when its readiness is stale.
        ResolvedRoutingPolicy policy;
        try {
            policy = routingPolicyResolver.resolve(principal.organizationId(), principal.projectId(),
                    authorized.logicalModelId(), now);
        } catch (IllegalStateException ex) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "No eligible Provider route is available for the requested model");
        }
        ResolvedRoutingPolicy.Candidate route = null;
        boolean skippedBefore = false;
        for (var candidate : routeSelector.orderedCandidates(policy)) {
            if (eligibilityEvaluator.evaluate(candidate, policy.logicalModelId(),
                    new CandidateEligibilityEvaluator.RequestCapabilities(true, command.streaming()),
                    Set.of()).eligible()) {
                route = candidate;
                break;
            }
            skippedBefore = true;
        }
        if (route == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "No eligible Provider route is available for the requested model");
        }
        var routeReasonCode = skippedBefore ? "INITIAL_FALLBACK" : "INITIAL_PRIMARY";

        // 5. Route attempt 1 PLANNED (append-only; a duplicate converges).
        var attempt = requestMapper.findFirstAttempt(principal.organizationId(), authorized.requestId());
        long attemptId;
        String routeDecisionId;
        if (attempt == null) {
            routeDecisionId = identityService.newRouteDecisionId();
            long requestId = authorized.requestId();
            try {
                requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                        principal.organizationId(), requestId, 1, routeDecisionId,
                        policy.id(), route.providerAccountId(), route.providerModelId(),
                        route.providerConnectionProfileId(), route.pricingVersionId(),
                        routeReasonCode));
            } catch (DuplicateKeyException ex) {
                // A concurrent identical call created the attempt between our
                // read and write; converge on the appended row.
                attempt = requestMapper.findFirstAttempt(principal.organizationId(), requestId);
                if (attempt == null) {
                    throw new IllegalStateException("Route attempt race could not converge", ex);
                }
                attemptId = attempt.id();
                routeDecisionId = attempt.routeDecisionId();
            }
            var persisted = requestMapper.findFirstAttempt(principal.organizationId(), requestId);
            if (persisted != null) {
                attemptId = persisted.id();
            } else {
                throw new IllegalStateException("Route attempt was not persisted");
            }
        } else {
            attemptId = attempt.id();
            routeDecisionId = attempt.routeDecisionId();
        }

        // 6. M12 TX1: MySQL-authoritative budget admission. TX1 persists its
        // terminal business result (RESERVED / UNBUDGETED / REJECTED_*) and
        // commits; rejections are mapped to errors here, after commit, so the
        // REJECTED_BUDGET state is durable and idempotent replay converges.
        // admitSync() owns the TX1 transaction boundary; never call the
        // blocking core directly or the Budget lock loses its transaction.
        AdmissionResult admission = reservationService.admitSync(new AdmissionCommand(
                principal, authorized.requestId(), attemptId, authorized.billingPeriodId(),
                route.pricingVersionId(), route.currency(),
                command.effectiveMaxOutputTokens(), -1L));
        if (admission.outcome() == AdmissionOutcome.REJECTED_BUDGET) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED,
                    "Budget is unavailable or insufficient for the conservative reservation");
        }
        if (admission.outcome() == AdmissionOutcome.REJECTED_DEPENDENCY) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Reservation cannot be safely evaluated for this request");
        }

        // 7. Durable financial safety fence (TX2); only then is Provider I/O legal.
        dispatchFenceService.commitDispatchFence(
                principal.organizationId(), authorized.requestId(), attemptId,
                authorized.billingPeriodId(), admission);

        return new DispatchResult(
                authorized.requestId(),
                authorized.publicRequestId(),
                attemptId,
                routeDecisionId,
                policy.id(),
                route.providerAccountId(),
                route.providerModelId(),
                route.pricingVersionId(),
                route.currency(),
                route.baseUrl(),
                route.adapterCode(),
                route.providerModelName(),
                command.logicalModelId(),
                authorized.maxOutputTokens(),
                authorized.defaultMaxOutputTokens(),
                authorized.billingPeriodId(),
                route.providerConnectionProfileId(),
                route.completionPath(),
                route.protocolCode(),
                route.networkPolicy());
    }

    private RequestState convergeExistingOrCreate(GatewayPrincipal principal,
            AuthorizeCommand command, byte[] idemDigest, byte[] fingerprint) {
        var orgId = principal.organizationId();
        var credentialId = principal.credentialId();
        var publicRequestId = identityService.newPublicRequestId();
        var key = GatewayIdempotencyHint.key(credentialId, idemDigest);
        var claim = idempotencyHint == null ? GatewayIdempotencyHint.Claim.UNAVAILABLE
                : idempotencyHint.claim(key, publicRequestId);
        Long durableRequestId = null;
        try {
            var existing = requestMapper.findByIdentity(orgId, credentialId, idemDigest);
            if (existing == null && claim == GatewayIdempotencyHint.Claim.CONTENDED) {
                existing = awaitRequestIdentity(orgId, credentialId, idemDigest);
            }
            if (existing != null) {
                durableRequestId = existing.id();
                return replayOrConverge(existing, fingerprint);
            }
            requestMapper.insertRequest(new GatewayRequestMapper.GatewayRequestInsert(
                    principal.organizationId(),
                    publicRequestId,
                    principal.credentialId(),
                    principal.principalType(),
                    principal.organizationMemberId(),
                    principal.serviceIdentityId(),
                    principal.projectId(),
                    principal.financialScopeType(),
                    principal.financialScopeId(),
                    command.logicalModelId(),
                    idemDigest,
                    fingerprint));
            durableRequestId = requestMapper.findByPublicRequestId(publicRequestId, orgId);
            if (durableRequestId == null) {
                throw new IllegalStateException("Gateway request was not persisted");
            }
            return new RequestState(durableRequestId, publicRequestId);
        } catch (DuplicateKeyException ex) {
            // A concurrent identical call won the insert; converge on it.
            var winner = requestMapper.findByIdentity(orgId, credentialId, idemDigest);
            if (winner == null) {
                throw new IllegalStateException("Idempotency race could not converge", ex);
            }
            durableRequestId = winner.id();
            return replayOrConverge(winner, fingerprint);
        } finally {
            if (claim == GatewayIdempotencyHint.Claim.OWNED) {
                idempotencyHint.finish(key, publicRequestId, durableRequestId);
            }
        }
    }

    private GatewayRequestMapper.ExistingRequestRow awaitRequestIdentity(
            long orgId, long credentialId, byte[] idemDigest) {
        long start = System.nanoTime();
        for (int delayMs = 2; System.nanoTime() - start < IDEMPOTENCY_WAIT_NANOS;
                delayMs = Math.min(delayMs * 2, 16)) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                        "Request identity lookup was interrupted");
            }
            var existing = requestMapper.findByIdentity(orgId, credentialId, idemDigest);
            if (existing != null) {
                return existing;
            }
        }
        // The Redis owner may have failed. The SQL unique key still arbitrates.
        return null;
    }

    private RequestState replayOrConverge(GatewayRequestMapper.ExistingRequestRow existing,
            byte[] fingerprint) {
        if (existing.requestFingerprint() == null
                || existing.requestFingerprint().length != fingerprint.length
                || !MessageDigest.isEqual(existing.requestFingerprint(), fingerprint)) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_IDEMPOTENCY_CONFLICT,
                    "The same Idempotency-Key was used with a different request body");
        }
        return switch (existing.state()) {
            case "VALIDATED", "RESERVED" ->
                    new RequestState(existing.id(), existing.publicRequestId());
            case "REJECTED_BUDGET" -> throw new GatewayErrorException(
                    GatewayErrorCode.GATEWAY_BUDGET_EXHAUSTED,
                    "This idempotency identity was rejected for mandatory budget");
            case "TRANSPORT_COMPLETED" -> throw new GatewayErrorException(
                    GatewayErrorCode.GATEWAY_RESPONSE_NOT_RETAINED,
                    "This idempotency identity already completed and its response is not retained");
            case "FAILED_PRE_DISPATCH" -> throw new GatewayErrorException(
                    GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                    "This idempotency identity already ended without a Provider dispatch");
            default -> throw new GatewayErrorException(
                    GatewayErrorCode.GATEWAY_REQUEST_IN_PROGRESS,
                    "This idempotency identity is still being processed or is financially uncertain");
        };
    }

    public record AuthorizeCommand(
            GatewayPrincipal principal,
            long logicalModelId,
            byte[] rawBodyBytes,
            String rawIdempotencyKey,
            long effectiveMaxOutputTokens,
            boolean streaming,
            GatewayReadMapper.ModelRow preResolvedModel) {

        public AuthorizeCommand(GatewayPrincipal principal, long logicalModelId, byte[] rawBodyBytes,
                String rawIdempotencyKey, long effectiveMaxOutputTokens) {
            this(principal, logicalModelId, rawBodyBytes, rawIdempotencyKey,
                    effectiveMaxOutputTokens, false, null);
        }

        public AuthorizeCommand(GatewayPrincipal principal, long logicalModelId, byte[] rawBodyBytes,
                String rawIdempotencyKey, long effectiveMaxOutputTokens, boolean streaming) {
            this(principal, logicalModelId, rawBodyBytes, rawIdempotencyKey,
                    effectiveMaxOutputTokens, streaming, null);
        }
    }

    public record DispatchResult(
            long requestId,
            String publicRequestId,
            long routeAttemptId,
            String routeDecisionId,
            long routingPolicyId,
            long providerAccountId,
            long providerModelId,
            long pricingVersionId,
            String currency,
            String baseUrl,
            String adapterCode,
            String providerModelName,
            long logicalModelId,
            int maxOutputTokens,
            Integer defaultMaxOutputTokens,
            long billingPeriodId,
            long providerConnectionProfileId,
            String completionPath,
            String protocolCode,
            String networkPolicy) {
    }

    public record AuthorizedRequest(
            long requestId,
            String publicRequestId,
            GatewayPrincipal principal,
            AuthorizeCommand command,
            long logicalModelId,
            String modelKey,
            int maxOutputTokens,
            Integer defaultMaxOutputTokens,
            long billingPeriodId) {
    }

    private record RequestState(long requestId, String publicRequestId) {
    }
}
