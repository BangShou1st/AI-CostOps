package com.aicostops.gateway.web;

import com.aicostops.gateway.auth.GatewayBearerWebFilter;
import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.config.BlockingIoScheduler;
import com.aicostops.gateway.config.GatewayResourceLimiter;
import com.aicostops.gateway.observability.CorrelationWebFilter;
import com.aicostops.gateway.observability.GatewayMetrics;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.metering.GatewayUsageFinalizationService;
import com.aicostops.gateway.metering.GatewayUsageObservation;
import com.aicostops.gateway.metering.GatewayUsageStatus;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapterRegistry;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.quota.GatewayQuotaLimiter;
import com.aicostops.gateway.ratelimit.GatewayRateLimiter;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.request.GatewayRequestLifecycleService;
import com.aicostops.gateway.request.GatewayRequestOrchestrator;
import com.aicostops.gateway.request.GatewayRequestOrchestrator.PreparedDispatch;
import com.aicostops.gateway.resilience.CircuitBreakerService;
import com.aicostops.gateway.resilience.RouteCircuitKey;
import com.aicostops.gateway.request.GatewayRequestService;
import com.aicostops.gateway.request.GatewayRequestService.AuthorizeCommand;
import com.aicostops.gateway.request.GatewayRequestService.DispatchResult;
import com.aicostops.gateway.request.StreamingLifecycleService;
import com.aicostops.gateway.web.dto.ChatCompletionRequest;
import com.aicostops.gateway.web.dto.ChatCompletionRequestParser;
import com.aicostops.gateway.web.dto.ChatCompletionResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded OpenAI-compatible edge: authenticate, enforce the 1 MiB decoded
 * body limit, validate the M11 subset, commit the durable dispatch fence, and
 * only then call the Provider adapter. {@code stream=true} proxies the upstream
 * SSE incrementally with a single terminal {@code data: [DONE]}. Never
 * retries upstream after dispatch; post-dispatch cancel/timeout/failure are
 * persisted as {@code CANCELED_AFTER_DISPATCH} / {@code TIMED_OUT_AFTER_DISPATCH}
 * / {@code FAILED_AFTER_DISPATCH} with the route attempt left
 * {@code BILLABLE_POSSIBLE}.
 */
@RestController
@RequestMapping(path = "/v1")
public class ChatCompletionController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REQUEST_ID_HEADER = "X-AI-CostOps-Request-Id";
    private static final String TRACE_HEADER = "X-Trace-Id";

    private final GatewayReadMapper readMapper;
    private final GatewayRequestService requestService;
    private final GatewayRequestOrchestrator orchestrator;
    private final CircuitBreakerService circuits;
    private final GatewayRequestLifecycleService lifecycleService;
    private final StreamingLifecycleService streamingLifecycle;
    private final ProviderCredentialDecryptor credentialDecryptor;
    private final ProviderChatAdapterRegistry adapterRegistry;
    private final GatewayUsageFinalizationService usageFinalization;
    private final GatewaySseEncoder sseEncoder;
    private final GatewayRateLimiter rateLimiter;
    private final GatewayQuotaLimiter quotaLimiter;
    private final GatewayResourceLimiter resourceLimiter;
    private final GatewayMetrics metrics;
    private final ObjectMapper objectMapper;
    private final BlockingIoScheduler blockingIo;
    private final Clock clock;
    private final int maxRequestBytes;

    public ChatCompletionController(
            GatewayReadMapper readMapper,
            GatewayRequestService requestService,
            GatewayRequestOrchestrator orchestrator,
            CircuitBreakerService circuits,
            GatewayRequestLifecycleService lifecycleService,
            StreamingLifecycleService streamingLifecycle,
            ProviderCredentialDecryptor credentialDecryptor,
            ProviderChatAdapterRegistry adapterRegistry,
            GatewayUsageFinalizationService usageFinalization,
            GatewaySseEncoder sseEncoder,
            GatewayRateLimiter rateLimiter,
            GatewayQuotaLimiter quotaLimiter,
            GatewayResourceLimiter resourceLimiter,
            GatewayMetrics metrics,
            ObjectMapper objectMapper,
            com.aicostops.gateway.config.GatewayProperties properties,
            BlockingIoScheduler blockingIo,
            Clock clock) {
        this.readMapper = readMapper;
        this.requestService = requestService;
        this.orchestrator = orchestrator;
        this.circuits = circuits;
        this.lifecycleService = lifecycleService;
        this.streamingLifecycle = streamingLifecycle;
        this.credentialDecryptor = credentialDecryptor;
        this.adapterRegistry = adapterRegistry;
        this.usageFinalization = usageFinalization;
        this.sseEncoder = sseEncoder;
        this.rateLimiter = rateLimiter;
        this.quotaLimiter = quotaLimiter;
        this.resourceLimiter = resourceLimiter;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.blockingIo = blockingIo;
        this.clock = clock;
        this.maxRequestBytes = properties.getMaxRequestBytes();
    }

    @PostMapping(path = "/chat/completions", consumes = "application/json")
    public Mono<ResponseEntity<?>> createChatCompletion(
            ServerWebExchange exchange) {
        return principal(exchange)
                .flatMap(principal -> readBoundedBody(exchange)
                        .flatMap(rawBody -> resolveCatalog(rawBody)
                                .flatMap(catalog -> handleRequest(
                                        exchange, principal, rawBody, catalog))));
    }

    private Mono<ResponseEntity<?>> handleRequest(
            ServerWebExchange exchange, GatewayPrincipal principal, byte[] rawBody,
            ResolvedCatalogModel catalog) {
        var request = ChatCompletionRequestParser.parse(rawBody, objectMapper);
        var idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                    "Idempotency-Key header is required for billable requests");
        }
        if (!catalog.modelKey().equals(request.model())) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                    "Unknown model");
        }
        var modelId = catalog.modelId();
        var effectiveMaxTokens = resolveEffectiveMaxTokens(request, catalog);

        // A stream permit is held for the whole streaming lifetime and always
        // released (complete/error/cancel, or any early failure in this chain).
        var permitHeld = new AtomicBoolean(false);
        if (request.stream()) {
            if (!resourceLimiter.tryAcquireStreamPermit()) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_RATE_LIMITED,
                        "Too many concurrent streams");
            }
            permitHeld.set(true);
        }
        Runnable releasePermit = () -> {
            if (permitHeld.getAndSet(false)) {
                resourceLimiter.releaseStreamPermit();
            }
        };

        return rateLimiter.tryAcquire(principal.credentialId())
                .onErrorResume(ex -> {
                    metrics.recordRedisDependencyError();
                    metrics.recordRequestOutcome("DEPENDENCY_UNAVAILABLE");
                    return Mono.error(ex);
                })
                .flatMap(rateResult -> {
                    if (!rateResult.allowed()) {
                        metrics.recordRequestOutcome("RATE_LIMITED");
                        throw new GatewayErrorException(GatewayErrorCode.GATEWAY_RATE_LIMITED,
                                "Rate limit exceeded", retryAfterSeconds(rateResult));
                    }
                    return quotaLimiter.tryAcquire(principal.credentialId())
                            .onErrorResume(ex -> {
                                metrics.recordRedisDependencyError();
                                metrics.recordQuota("DEPENDENCY_UNAVAILABLE");
                                metrics.recordRequestOutcome("DEPENDENCY_UNAVAILABLE");
                                return Mono.error(ex);
                            });
                })
                .flatMap(quotaResult -> {
                    if (!quotaResult.allowed()) {
                        metrics.recordQuota("REJECTED");
                        metrics.recordRequestOutcome("RATE_LIMITED");
                        throw new GatewayErrorException(GatewayErrorCode.GATEWAY_RATE_LIMITED,
                                "Daily request quota exceeded");
                    }
                    metrics.recordQuota("ALLOWED");
                    return orchestrator.prepareInitial(new AuthorizeCommand(
                            principal, modelId, rawBody, idempotencyKey,
                            effectiveMaxTokens, request.stream()), request.stream());
                })
                .flatMap(prepared -> {
                    return request.stream()
                            ? invokeStream(exchange, principal, prepared, request,
                                    effectiveMaxTokens, releasePermit)
                                    .map(entity -> (ResponseEntity<?>) entity)
                            : invokeProviderWithFailover(exchange, principal, prepared, request, effectiveMaxTokens)
                                    .map(completed -> (ResponseEntity<?>) ResponseEntity.ok(
                                            buildResponse(completed.dispatch(), request,
                                                    completed.completion())));
                })
                .doFinally(ignored -> releasePermit.run());
    }

    private Mono<ResolvedCatalogModel> resolveCatalog(byte[] rawBody) {
        var request = ChatCompletionRequestParser.parse(rawBody, objectMapper);
        var modelKey = request.model();
        // Synchronous JDBC/MyBatis catalog reads run strictly on the dedicated
        // gateway-db scheduler, never the Reactor Netty event loop.
        return blockingIo.call(() -> {
            var modelId = readMapper.findModelIdByKey(modelKey);
            if (modelId == null) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                        "Unknown model");
            }
            var model = readMapper.findModelById(modelId);
            if (model == null || !modelKey.equals(model.modelKey())) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                        "Unknown model");
            }
            if (!"ACTIVE".equals(model.status())) {
                throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                        "Unknown model");
            }
            return new ResolvedCatalogModel(model.id(), model.modelKey(),
                    model.maxOutputTokens(), model.defaultMaxOutputTokens());
        });
    }

    private record ResolvedCatalogModel(
            long modelId, String modelKey, int maxOutputTokens, Integer defaultMaxOutputTokens) {
    }

    private Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> invokeStream(
            ServerWebExchange exchange, GatewayPrincipal principal, PreparedDispatch initial,
            ChatCompletionRequest request, int effectiveMaxTokens, Runnable releasePermit) {
        setCorrelationHeaders(exchange, initial.publicRequestId());
        var current = new AtomicReference<>(initial);
        var emitted = new AtomicBoolean(false);
        var command = new ChatCompletionCommand(
                request.model(),
                request.messages().stream()
                        .map(message -> new ChatCompletionCommand.Message(
                                message.role(), message.content()))
                        .toList(),
                effectiveMaxTokens,
                true);
        Flux<ServerSentEvent<String>> body = Flux.defer(() -> streamAttempt(
                        exchange, principal, current, emitted, command, request))
                .doOnCancel(() -> finalizeCancellation(current.get()))
                .doFinally(ignored -> releasePermit.run());
        return Mono.just(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body));
    }

    private Flux<ServerSentEvent<String>> streamAttempt(
            ServerWebExchange exchange, GatewayPrincipal principal,
            AtomicReference<PreparedDispatch> current, AtomicBoolean emitted,
            ChatCompletionCommand command, ChatCompletionRequest request) {
        var prepared = current.get();
        var result = prepared.dispatch();
        var upstreamDone = new AtomicBoolean(false);
        var latestMetering = new AtomicReference<GatewayUsageObservation>();
        var fallbackId = "cmpl_" + prepared.publicRequestId();
        var requestId = prepared.requestId();
        var orgId = principal.organizationId();
        return buildProviderContext(principal, result)
                .flatMapMany(context -> lifecycleService
                        .beginUpstream(requestId, orgId, result.routeAttemptId())
                        .thenMany(adapterRegistry.require(result.adapterCode()).stream(context, command)))
                .takeWhile(event -> {
                    if (event instanceof ProviderChatStreamEvent.Done) {
                        upstreamDone.set(true);
                        return false;
                    }
                    return true;
                })
                .doOnNext(event -> {
                    if (event instanceof ProviderChatStreamEvent.Metering metering) {
                        latestMetering.set(GatewayUsageObservation.fromMetering(metering, null));
                    }
                })
                .filter(ProviderChatStreamEvent.Delta.class::isInstance)
                .map(event -> (ProviderChatStreamEvent.Delta) event)
                .map(delta -> {
                    emitted.set(true);
                    return ServerSentEvent.<String>builder()
                            .data(" " + sseEncoder.encodeChunk(delta, request.model(), fallbackId))
                            .build();
                })
                .concatWith(Flux.defer(() -> {
                    if (!upstreamDone.get()) {
                        return Flux.error(new GatewayErrorException(
                                GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                                "Provider stream ended without a terminal signal"));
                    }
                    var observation = latestMetering.get();
                    if (observation == null) {
                        observation = GatewayUsageObservation.noUsage(null).withDispatched(true);
                    }
                    var finalObservation = observation;
                    return circuits.recordSuccess(new RouteCircuitKey(orgId,
                                    result.providerAccountId(), result.providerModelId()))
                            .then(usageFinalization.finalizeSuccess(
                                    requestId, orgId, result.routeAttemptId(), finalObservation))
                            .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                    "POSTDISPATCH_UNCERTAINTY"))
                            .thenMany(Flux.just(ServerSentEvent.<String>builder()
                                    .data(" " + GatewaySseEncoder.DONE_PAYLOAD)
                                    .build()));
                }))
                .onErrorResume(ex -> handleStreamFailure(
                        exchange, principal, current, emitted, command, request, prepared,
                        latestMetering.get(), ex));
    }

    private Flux<ServerSentEvent<String>> handleStreamFailure(
            ServerWebExchange exchange, GatewayPrincipal principal,
            AtomicReference<PreparedDispatch> current, AtomicBoolean emitted,
            ChatCompletionCommand command, ChatCompletionRequest request,
            PreparedDispatch prepared, GatewayUsageObservation latestObservation,
            Throwable error) {
        var failure = asProviderFailure(error);
        var result = prepared.dispatch();
        metrics.recordProviderSafety(result.adapterCode(), failure.safetyOutcome().name(),
                failure.safetyReason().name());
        var key = new RouteCircuitKey(principal.organizationId(),
                result.providerAccountId(), result.providerModelId());
        var recordFailure = recordCircuitFailure(key, failure);
        if (!emitted.get() && failure.safetyOutcome() == ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION) {
            return recordFailure.thenMany(orchestrator.prepareNextSafe(prepared, failure)
                    .doOnNext(current::set)
                    .doOnNext(ignored -> metrics.recordFailover("ADVANCED", "SAFE_NO_BILLABLE_EXECUTION"))
                    .flatMapMany(next -> streamAttempt(
                            exchange, principal, current, emitted, command, request)));
        }
        var observation = latestObservation == null
                ? failureObservation(failure) : latestObservation.withDispatched(true);
        var transportFailure = isTimeout(failure)
                ? GatewayUsageFinalizationService.TransportFailure.TIMED_OUT
                : GatewayUsageFinalizationService.TransportFailure.FAILED;
        metrics.recordRequestOutcome(isTimeout(failure) ? "TIMED_OUT" : "FAILED");
        metrics.recordProviderError(result.adapterCode(), isTimeout(failure) ? "TIMEOUT" : "HTTP_ERROR");
        return recordFailure.then(finalizeProviderFailure(
                        prepared, observation, transportFailure, failure))
                .thenMany(Flux.error(failure));
    }

    private Mono<CompletedDispatch> invokeProviderWithFailover(
            ServerWebExchange exchange, GatewayPrincipal principal, PreparedDispatch prepared,
            ChatCompletionRequest request, int effectiveMaxTokens) {
        setCorrelationHeaders(exchange, prepared.publicRequestId());
        return invokeProviderOnce(exchange, principal, prepared, request, effectiveMaxTokens)
                .map(completion -> new CompletedDispatch(prepared.dispatch(), completion))
                .onErrorResume(error -> {
                    var failure = asProviderFailure(error);
                    var result = prepared.dispatch();
                    metrics.recordProviderSafety(result.adapterCode(), failure.safetyOutcome().name(),
                            failure.safetyReason().name());
                    var recordFailure = recordCircuitFailure(new RouteCircuitKey(principal.organizationId(),
                            result.providerAccountId(), result.providerModelId()), failure);
                    if (failure.safetyOutcome() == ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION) {
                        return recordFailure.then(orchestrator.prepareNextSafe(prepared, failure))
                                .doOnSuccess(ignored -> metrics.recordFailover(
                                        "ADVANCED", "SAFE_NO_BILLABLE_EXECUTION"))
                                .flatMap(next -> invokeProviderWithFailover(
                                        exchange, principal, next, request, effectiveMaxTokens));
                    }
                    var observation = error instanceof ProviderAttemptFailure attemptFailure
                            ? attemptFailure.observation() : failureObservation(failure);
                    return recordFailure.then(finalizeProviderFailure(prepared, observation,
                                    isTimeout(failure)
                                            ? GatewayUsageFinalizationService.TransportFailure.TIMED_OUT
                                            : GatewayUsageFinalizationService.TransportFailure.FAILED,
                                    failure))
                            .then(Mono.error(failure));
                });
    }

    private Mono<ProviderChatCompletion> invokeProviderOnce(
            ServerWebExchange exchange, GatewayPrincipal principal, PreparedDispatch prepared,
            ChatCompletionRequest request, int effectiveMaxTokens) {
        var result = prepared.dispatch();
        var requestId = prepared.requestId();
        var orgId = principal.organizationId();
        var latestObservation = new AtomicReference<GatewayUsageObservation>();
        return buildProviderContext(principal, result)
                .flatMap(context -> {
                    var command = new ChatCompletionCommand(
                            request.model(),
                            request.messages().stream()
                                    .map(message -> new ChatCompletionCommand.Message(
                                            message.role(), message.content()))
                                    .toList(),
                            effectiveMaxTokens,
                            false);
                    return lifecycleService.beginUpstream(requestId, orgId, result.routeAttemptId())
                            .then(adapterRegistry.require(result.adapterCode()).complete(context, command));
                })
                .flatMap(completion -> {
                    var observation = GatewayUsageObservation.fromCompletion(completion, null);
                    latestObservation.set(observation);
                    return circuits.recordSuccess(new RouteCircuitKey(orgId,
                                    result.providerAccountId(), result.providerModelId()))
                            .then(usageFinalization.finalizeSuccess(
                                    requestId, orgId, result.routeAttemptId(), observation))
                            .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                    "MISSING_DIMENSION"))
                            .thenReturn(completion);
                })
                .doOnSuccess(ignored -> metrics.recordRequestOutcome("COMPLETED"))
                .onErrorMap(error -> {
                    if (error instanceof ProviderAttemptFailure) return error;
                    var observation = latestObservation.get();
                    return observation == null ? error : new ProviderAttemptFailure(
                            asProviderFailure(error), observation);
                });
    }

    private Mono<Void> finalizeProviderFailure(PreparedDispatch prepared,
            GatewayUsageObservation observation,
            GatewayUsageFinalizationService.TransportFailure transportFailure,
            ProviderExecutionException failure) {
        var result = prepared.dispatch();
        return usageFinalization.finalizeFailure(
                        prepared.requestId(), prepared.principal().organizationId(),
                        result.routeAttemptId(), observation, transportFailure)
                .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                        "POSTDISPATCH_UNCERTAINTY"))
                .then()
                .onErrorResume(finalizationFailure -> isTimeout(failure)
                        ? streamingLifecycle.timeoutAfterDispatch(
                                prepared.requestId(), prepared.principal().organizationId())
                        : lifecycleService.failAfterDispatch(
                                prepared.requestId(), prepared.principal().organizationId()));
    }

    private static GatewayUsageObservation failureObservation(ProviderExecutionException failure) {
        return new GatewayUsageObservation(null, null, null, null, false, true,
                null, null, null, failure.providerRequestId(), null, Map.of());
    }

    private static ProviderExecutionException asProviderFailure(Throwable error) {
        var unwrapped = reactor.core.Exceptions.unwrap(error);
        if (unwrapped instanceof ProviderAttemptFailure attemptFailure) {
            return attemptFailure.failure();
        }
        if (unwrapped instanceof ProviderExecutionException providerFailure) {
            return providerFailure;
        }
        var timeout = isTimeout(unwrapped);
        return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                timeout ? com.aicostops.gateway.provider.ProviderSafetyReason.HEADER_TIMEOUT_WRITE_POSSIBLE
                        : com.aicostops.gateway.provider.ProviderSafetyReason.UNKNOWN_POST_DISPATCH,
                ProviderHealthSignal.QUALIFYING_FAILURE, null, null, true, unwrapped);
    }

    private Mono<Void> recordCircuitFailure(RouteCircuitKey key, ProviderExecutionException failure) {
        var signal = failure.healthSignal();
        if (signal == null || signal == ProviderHealthSignal.NONE
                || signal == ProviderHealthSignal.SUCCESS
                || signal == ProviderHealthSignal.CLIENT_CANCELLATION) {
            return Mono.empty();
        }
        return circuits.recordFailure(key, signal)
                .onErrorResume(ignored -> Mono.empty());
    }

    private void finalizeCancellation(PreparedDispatch prepared) {
        var result = prepared.dispatch();
        metrics.recordRequestOutcome("CANCELED");
        usageFinalization.finalizeFailure(
                        prepared.requestId(), prepared.principal().organizationId(),
                        result.routeAttemptId(), GatewayUsageObservation.noUsage(null)
                                .withDispatched(true),
                        GatewayUsageFinalizationService.TransportFailure.CANCELED)
                .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                        "POSTDISPATCH_UNCERTAINTY"))
                .then()
                .onErrorResume(ignored -> streamingLifecycle.cancelAfterDispatch(
                        prepared.requestId(), prepared.principal().organizationId()))
                .subscribe();
    }

    private static final class ProviderAttemptFailure extends RuntimeException {

        private final ProviderExecutionException failure;
        private final GatewayUsageObservation observation;

        private ProviderAttemptFailure(ProviderExecutionException failure,
                GatewayUsageObservation observation) {
            super(failure);
            this.failure = failure;
            this.observation = observation;
        }

        private ProviderExecutionException failure() { return failure; }

        private GatewayUsageObservation observation() { return observation; }
    }

    private record CompletedDispatch(DispatchResult dispatch, ProviderChatCompletion completion) {
    }

    /**
     * Provider credential decryption performs a synchronous MyBatis read, so it
     * runs on the dedicated gateway-db scheduler, never the event loop.
     */
    private Mono<ProviderCallContext> buildProviderContext(
            GatewayPrincipal principal, DispatchResult result) {
        return blockingIo.call(() -> {
            var credential = credentialDecryptor.decrypt(principal.organizationId(), result.providerAccountId());
            return new ProviderCallContext(
                    result.adapterCode(),
                    result.providerAccountId(),
                    result.providerModelId(),
                    result.providerModelName(),
                    result.pricingVersionId(),
                    result.currency(),
                    result.baseUrl(),
                    credential.credentialType(),
                    credential.secret(),
                    result.routeDecisionId());
        });
    }

    private Map<String, Object> buildResponse(DispatchResult result, ChatCompletionRequest request,
            ProviderChatCompletion completion) {
        var choices = new ArrayList<ChatCompletionResponse.Choice>();
        int index = 0;
        for (var choice : completion.choices()) {
            choices.add(new ChatCompletionResponse.Choice(
                    index++,
                    new ChatCompletionResponse.Message("assistant", choice.content()),
                    choice.finishReason()));
        }
        ChatCompletionResponse.Usage usage = null;
        if (completion.usage() != null) {
            usage = new ChatCompletionResponse.Usage(
                    completion.usage().promptTokens(),
                    completion.usage().completionTokens(),
                    completion.usage().totalTokens());
        }
        var response = new ChatCompletionResponse(
                "cmpl_" + result.publicRequestId(),
                "chat.completion",
                Instant.now(clock).getEpochSecond(),
                request.model(),
                choices,
                usage);
        return response.toJsonValue();
    }

    private int resolveEffectiveMaxTokens(ChatCompletionRequest request,
            ResolvedCatalogModel model) {
        Integer effective = request.maxCompletionTokens() != null
                ? request.maxCompletionTokens() : model.defaultMaxOutputTokens();
        if (effective == null || effective < 1 || effective > model.maxOutputTokens()) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                    "A finite output ceiling could not be resolved within the governed model maximum");
        }
        return effective;
    }

    private Mono<byte[]> readBoundedBody(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        rejectRequestContentEncoding(request);
        // Known Content-Length above the bound rejects early with 413. Unknown
        // (chunked) length must enter the bounded reader below instead of being
        // mapped to Long.MAX_VALUE, which would 413 every small chunked request.
        var declaredLength = request.getHeaders().getContentLength();
        if (declaredLength > maxRequestBytes) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_TOO_LARGE,
                    "Request body exceeds the 1 MiB limit");
        }
        // join(max+1) guarantees the max+1 boundary byte trips the limit: a body
        // of exactly maxRequestBytes+1 is rejected, never accepted.
        return DataBufferUtils.join(request.getBody(), maxRequestBytes + 1)
                .map(buffer -> {
                    try {
                        var size = buffer.readableByteCount();
                        if (size > maxRequestBytes) {
                            throw new GatewayErrorException(
                                    GatewayErrorCode.GATEWAY_REQUEST_TOO_LARGE,
                                    "Request body exceeds the 1 MiB limit");
                        }
                        var bytes = new byte[size];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorMap(ex -> ex instanceof DataBufferLimitException
                        ? new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_TOO_LARGE,
                        "Request body exceeds the 1 MiB limit")
                        : ex);
    }

    /** Frozen M10/M11 contract: UTF-8 application/json with no request content-encoding. */
    private static void rejectRequestContentEncoding(ServerHttpRequest request) {
        var encodings = request.getHeaders().get(HttpHeaders.CONTENT_ENCODING);
        if (encodings == null || encodings.isEmpty()) {
            return;
        }
        for (var encoding : encodings) {
            if (encoding == null) {
                continue;
            }
            for (var token : encoding.split(",")) {
                var value = token.strip().toLowerCase(Locale.ROOT);
                if (!value.isEmpty() && !"identity".equals(value)) {
                    throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                            "Request Content-Encoding is not supported");
                }
            }
        }
    }

    private static Mono<GatewayPrincipal> principal(ServerWebExchange exchange) {
        var principal = exchange.getAttribute(GatewayBearerWebFilter.PRINCIPAL_ATTRIBUTE);
        if (principal instanceof GatewayPrincipal gatewayPrincipal) {
            return Mono.just(gatewayPrincipal);
        }
        return Mono.error(new GatewayErrorException(GatewayErrorCode.GATEWAY_AUTH_INVALID,
                "A valid Gateway key is required"));
    }

    private void setCorrelationHeaders(ServerWebExchange exchange, String requestId) {
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId(exchange));
    }

    private static String traceId(ServerWebExchange exchange) {
        var traceId = exchange.getAttribute(CorrelationWebFilter.TRACE_ATTRIBUTE);
        if (traceId instanceof String value && !value.isBlank()) {
            return value;
        }
        var existing = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        return existing == null || existing.isBlank() ? "trc_" + UUID.randomUUID() : existing;
    }

    /** Bounded Retry-After seconds for the OpenAI-compatible 429/503 envelope. */
    private static Integer retryAfterSeconds(GatewayRateLimiter.RateLimitResult result) {
        return (int) Math.max(1, (result.retryAfterMillis() + 999) / 1000);
    }

    /** Provider timeout classes become TIMED_OUT_AFTER_DISPATCH, not FAILED_AFTER_DISPATCH. */
    private static boolean isTimeout(Throwable ex) {
        var unwrapped = reactor.core.Exceptions.unwrap(ex);
        if (unwrapped instanceof GatewayErrorException gatewayError
                && gatewayError.code() == GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT) {
            return true;
        }
        for (Throwable current = unwrapped;
                current != null && current != current.getCause();
                current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException
                    || current instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private void recordUsage(
            GatewayUsageFinalizationService.FinalizationResult result,
            String providerCode, String reasonCode) {
        if (result == null) {
            return;
        }
        metrics.recordUsageStatus(result.status());
        if (result.status() == GatewayUsageStatus.INCOMPLETE) {
            metrics.recordMeteringIncomplete(providerCode, reasonCode);
        } else if (result.status() == GatewayUsageStatus.UNKNOWN) {
            metrics.recordMeteringUnknown(providerCode, reasonCode);
        }
    }
}
