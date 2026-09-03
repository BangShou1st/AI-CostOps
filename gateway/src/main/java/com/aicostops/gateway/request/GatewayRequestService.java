package com.aicostops.gateway.request;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
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

    private static final String M11_PROVIDER_CODE = "MIMO";
    private static final Set<String> IN_PROGRESS_STATES = Set.of(
            "DISPATCH_INTENT", "UPSTREAM_ACTIVE", "CANCELED_AFTER_DISPATCH",
            "TIMED_OUT_AFTER_DISPATCH", "FAILED_AFTER_DISPATCH");

    private final GatewayReadMapper readMapper;
    private final GatewayRequestMapper requestMapper;
    private final RequestIdentityService identityService;
    private final DispatchFenceService dispatchFenceService;
    private final BudgetReservationService reservationService;
    private final BlockingIoScheduler blockingIo;
    private final Clock clock;

    public GatewayRequestService(
            GatewayReadMapper readMapper,
            GatewayRequestMapper requestMapper,
            RequestIdentityService identityService,
            DispatchFenceService dispatchFenceService,
            BudgetReservationService reservationService,
            BlockingIoScheduler blockingIo,
            Clock clock) {
        this.readMapper = readMapper;
        this.requestMapper = requestMapper;
        this.identityService = identityService;
        this.dispatchFenceService = dispatchFenceService;
        this.reservationService = reservationService;
        this.blockingIo = blockingIo;
        this.clock = clock;
    }

    public Mono<DispatchResult> authorizeAndFence(AuthorizeCommand command) {
        return blockingIo.call(() -> authorizeAndFenceBlocking(command));
    }

    private DispatchResult authorizeAndFenceBlocking(AuthorizeCommand command) {
        var now = Instant.now(clock);
        var principal = command.principal();

        identityService.validateIdempotencyKey(command.rawIdempotencyKey());
        var idemDigest = identityService.idempotencyKeyDigest(command.rawIdempotencyKey());
        var fingerprint = identityService.requestFingerprint(command.rawBodyBytes());

        // 1. Explicit credential-model allowlist (deny by default) + active model.
        if (!readMapper.findActiveModelIds(principal.credentialId())
                .contains(command.logicalModelId())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "The requested model is not allowed for this credential");
        }
        var model = readMapper.findModelById(command.logicalModelId());
        if (model == null || !"ACTIVE".equals(model.status())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "The requested model is not active");
        }

        // 2. Server-governed single Provider route (M11): active Provider
        // Account, active Provider Credential, eligible Provider Model and a
        // resolvable ACTIVE Pricing Version.
        var route = readMapper.findRouteCandidate(principal.organizationId(),
                command.logicalModelId(), M11_PROVIDER_CODE, now);
        if (route == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_FORBIDDEN,
                    "No eligible Provider route is available for the requested model");
        }

        // 3. M12 TX1 MySQL-authoritative budget admission is resolved after
        // the VALIDATED request and PLANNED attempt exist (step 5/6 below);
        // REQUIRED without a matching/insufficient Budget fails there.

        // 4. Resolve the OPEN BillingPeriod financial fence for dispatch time.
        var periodId = readMapper.findOpenBillingPeriodId(principal.organizationId(), now);
        if (periodId == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "No open billing period is available for dispatch");
        }

        // 5. Converge or create the durable VALIDATED request.
        var request = convergeExistingOrCreate(principal, command, idemDigest, fingerprint);

        // 6. Route attempt 1 PLANNED (append-only; a duplicate converges).
        var attempt = requestMapper.findFirstAttempt(principal.organizationId(), request.requestId());
        long attemptId;
        String routeDecisionId;
        if (attempt == null) {
            routeDecisionId = identityService.newRouteDecisionId();
            long requestId = request.requestId();
            try {
                requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                        principal.organizationId(), requestId, routeDecisionId,
                        route.providerAccountId(), route.providerModelId(), route.pricingVersionId()));
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

        // 7. M12 TX1: MySQL-authoritative budget admission. REQUIRED without
        // a matching/insufficient Budget (or an unsafe bound) fails closed
        // here; OPTIONAL without a matching Budget is explicitly unbudgeted.
        // admitSync() owns the TX1 transaction boundary; never call the
        // blocking core directly or the Budget lock loses its transaction.
        AdmissionResult admission = reservationService.admitSync(new AdmissionCommand(
                principal, request.requestId(), attemptId, periodId,
                route.pricingVersionId(), route.currency(),
                command.effectiveMaxOutputTokens(), -1L));

        // 8. Durable financial safety fence (TX2); only then is Provider I/O legal.
        dispatchFenceService.commitDispatchFence(
                principal.organizationId(), request.requestId(), attemptId, periodId,
                admission);

        return new DispatchResult(
                request.requestId(),
                request.publicRequestId(),
                attemptId,
                routeDecisionId,
                route.providerAccountId(),
                route.providerModelId(),
                route.pricingVersionId(),
                route.currency(),
                route.baseUrl(),
                route.adapterCode(),
                route.providerModelName(),
                command.logicalModelId(),
                model.maxOutputTokens(),
                model.defaultMaxOutputTokens(),
                periodId);
    }

    private RequestState convergeExistingOrCreate(GatewayPrincipal principal,
            AuthorizeCommand command, byte[] idemDigest, byte[] fingerprint) {
        var existing = requestMapper.findByIdentity(
                principal.organizationId(), principal.credentialId(), idemDigest);
        if (existing != null) {
            return replayOrConverge(existing, fingerprint);
        }
        try {
            var publicRequestId = identityService.newPublicRequestId();
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
            var requestId = requestMapper.findByPublicRequestId(publicRequestId,
                    principal.organizationId());
            if (requestId == null) {
                throw new IllegalStateException("Gateway request was not persisted");
            }
            return new RequestState(requestId, publicRequestId);
        } catch (DuplicateKeyException ex) {
            // A concurrent identical call won the insert; converge on it.
            var winner = requestMapper.findByIdentity(
                    principal.organizationId(), principal.credentialId(), idemDigest);
            if (winner == null) {
                throw new IllegalStateException("Idempotency race could not converge", ex);
            }
            return replayOrConverge(winner, fingerprint);
        }
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
            long effectiveMaxOutputTokens) {
    }

    public record DispatchResult(
            long requestId,
            String publicRequestId,
            long routeAttemptId,
            String routeDecisionId,
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
            long billingPeriodId) {
    }

    private record RequestState(long requestId, String publicRequestId) {
    }
}