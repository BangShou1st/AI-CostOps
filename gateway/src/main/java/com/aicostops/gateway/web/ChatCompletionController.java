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
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
import com.aicostops.gateway.quota.GatewayQuotaLimiter;
import com.aicostops.gateway.ratelimit.GatewayRateLimiter;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.request.GatewayRequestLifecycleService;
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
    private final GatewayRequestLifecycleService lifecycleService;
    private final StreamingLifecycleService streamingLifecycle;
    private final ProviderCredentialDecryptor credentialDecryptor;
    private final ProviderChatAdapter chatAdapter;
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
            GatewayRequestLifecycleService lifecycleService,
            StreamingLifecycleService streamingLifecycle,
            ProviderCredentialDecryptor credentialDecryptor,
            ProviderChatAdapter chatAdapter,
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
        this.lifecycleService = lifecycleService;
        this.streamingLifecycle = streamingLifecycle;
        this.credentialDecryptor = credentialDecryptor;
        this.chatAdapter = chatAdapter;
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
                    return requestService.authorizeAndFence(new AuthorizeCommand(
                            principal, modelId, rawBody, idempotencyKey,
                            effectiveMaxTokens));
                })
                .flatMap((DispatchResult result) -> request.stream()
                        ? invokeStream(exchange, principal, result, request,
                                effectiveMaxTokens, releasePermit)
                                .map(entity -> (ResponseEntity<?>) entity)
                        : invokeProvider(exchange, principal, result, request, effectiveMaxTokens)
                                .map(completion -> (ResponseEntity<?>) ResponseEntity.ok(
                                        buildResponse(result, request,
                                                completion))))
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
            ServerWebExchange exchange, GatewayPrincipal principal, DispatchResult result,
            ChatCompletionRequest request, int effectiveMaxTokens, Runnable releasePermit) {
        return buildProviderContext(principal, result).map(context -> {
            var command = new ChatCompletionCommand(
                    request.model(),
                    request.messages().stream()
                            .map(message -> new ChatCompletionCommand.Message(
                                    message.role(), message.content()))
                            .toList(),
                    effectiveMaxTokens,
                    true);
            setCorrelationHeaders(exchange, result.publicRequestId());

            var fallbackId = "cmpl_" + result.publicRequestId();
            var requestId = result.requestId();
            var orgId = principal.organizationId();
            var upstreamDone = new AtomicBoolean(false);
            var latestMetering = new AtomicReference<GatewayUsageObservation>();
            Flux<ServerSentEvent<String>> body = lifecycleService
                    .beginUpstream(requestId, orgId, result.routeAttemptId())
                    .thenMany(chatAdapter.stream(context, command))
                    // Record a genuine upstream terminal [DONE]; takeWhile stops
                    // the data flow at DONE without forwarding the marker itself.
                    .takeWhile(event -> {
                        if (event instanceof ProviderChatStreamEvent.Done) {
                            upstreamDone.set(true);
                            return false;
                        }
                        return true;
                    })
                    // Metering is bounded state only; no content or reasoning
                    // is accumulated and the usage-only frame is not forwarded.
                    .doOnNext(event -> {
                        if (event instanceof ProviderChatStreamEvent.Metering metering) {
                            latestMetering.set(GatewayUsageObservation.fromMetering(metering, null));
                        }
                    })
                    .filter(ProviderChatStreamEvent.Delta.class::isInstance)
                    .map(event -> (ProviderChatStreamEvent.Delta) event)
                    .map(delta -> ServerSentEvent.<String>builder()
                            // Leading space produces the OpenAI-compatible "data: " prefix:
                            // Spring's SSE writer writes "data:" + value without a space.
                            .data(" " + sseEncoder.encodeChunk(delta, request.model(), fallbackId))
                            .build())
                    // Exactly one downstream [DONE] only when the upstream
                    // protocol genuinely terminated with [DONE]. A clean EOF
                    // without [DONE] must never be synthesized into success.
                    .concatWith(Flux.defer(() -> {
                        if (!upstreamDone.get()) {
                            return Flux.error(new GatewayErrorException(
                                    GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                                    "Provider stream ended without a terminal signal"));
                        }
                        var observation = latestMetering.get();
                        if (observation == null) {
                            observation = GatewayUsageObservation.noUsage(null)
                                    .withDispatched(true);
                        }
                        return usageFinalization.finalizeSuccess(
                                        requestId, orgId, result.routeAttemptId(), observation)
                                .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                        "POSTDISPATCH_UNCERTAINTY"))
                                // Downstream [DONE] is intentionally after the
                                // local fact+lifecycle transaction commits.
                                .thenMany(Flux.just(ServerSentEvent.<String>builder()
                                        .data(" " + GatewaySseEncoder.DONE_PAYLOAD)
                                        .build()));
                    }))
                    .onErrorResume(ex -> {
                        var timeout = isTimeout(ex);
                        metrics.recordRequestOutcome(timeout ? "TIMED_OUT" : "FAILED");
                        metrics.recordProviderError(result.adapterCode(),
                                timeout ? "TIMEOUT" : "HTTP_ERROR");
                        var failure = timeout
                                ? GatewayUsageFinalizationService.TransportFailure.TIMED_OUT
                                : GatewayUsageFinalizationService.TransportFailure.FAILED;
                        var observation = latestMetering.get();
                        if (observation == null) {
                            observation = GatewayUsageObservation.noUsage(null)
                                    .withDispatched(true);
                        }
                        var terminal = usageFinalization.finalizeFailure(
                                        requestId, orgId, result.routeAttemptId(), observation, failure)
                                .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                        "POSTDISPATCH_UNCERTAINTY"))
                                .then()
                                .onErrorResume(finalizationFailure -> timeout
                                        ? streamingLifecycle.timeoutAfterDispatch(requestId, orgId)
                                        : lifecycleService.failAfterDispatch(requestId, orgId));
                        return terminal.then(Mono.error(ex));
                    })
                    .doOnComplete(() -> metrics.recordRequestOutcome("COMPLETED"))
                    .doOnCancel(() -> {
                        metrics.recordRequestOutcome("CANCELED");
                        var observation = latestMetering.get();
                        if (observation == null) {
                            observation = GatewayUsageObservation.noUsage(null)
                                    .withDispatched(true);
                        }
                        usageFinalization.finalizeFailure(
                                        requestId, orgId, result.routeAttemptId(), observation,
                                        GatewayUsageFinalizationService.TransportFailure.CANCELED)
                                .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                        "POSTDISPATCH_UNCERTAINTY"))
                                .then()
                                .onErrorResume(finalizationFailure ->
                                        streamingLifecycle.cancelAfterDispatch(requestId, orgId))
                                .subscribe();
                    })
                    .doFinally(ignored -> releasePermit.run());

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(body);
        });
    }

    private Mono<ProviderChatCompletion> invokeProvider(
            ServerWebExchange exchange, GatewayPrincipal principal, DispatchResult result,
            ChatCompletionRequest request, int effectiveMaxTokens) {
        setCorrelationHeaders(exchange, result.publicRequestId());
        var requestId = result.requestId();
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
                            .then(chatAdapter.complete(context, command));
                })
                .flatMap(completion -> {
                    var observation = GatewayUsageObservation.fromCompletion(completion, null);
                    latestObservation.set(observation);
                    return usageFinalization.finalizeSuccess(
                                    requestId, orgId, result.routeAttemptId(), observation)
                            .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                    "MISSING_DIMENSION"))
                            .thenReturn(completion);
                })
                .doOnSuccess(ignored -> metrics.recordRequestOutcome("COMPLETED"))
                .onErrorResume(ex -> {
                    var timeout = isTimeout(ex);
                    metrics.recordRequestOutcome(timeout ? "TIMED_OUT" : "FAILED");
                    metrics.recordProviderError(result.adapterCode(),
                            timeout ? "TIMEOUT" : "HTTP_ERROR");
                    var failure = timeout
                            ? GatewayUsageFinalizationService.TransportFailure.TIMED_OUT
                            : GatewayUsageFinalizationService.TransportFailure.FAILED;
                    var observation = latestObservation.get();
                    if (observation == null) {
                        observation = GatewayUsageObservation.noUsage(null).withDispatched(true);
                    }
                    var terminal = usageFinalization.finalizeFailure(
                                    requestId, orgId, result.routeAttemptId(),
                                    observation, failure)
                            .doOnSuccess(outcome -> recordUsage(outcome, result.adapterCode(),
                                    "POSTDISPATCH_UNCERTAINTY"))
                            .then()
                            .onErrorResume(finalizationFailure -> timeout
                                    ? streamingLifecycle.timeoutAfterDispatch(requestId, orgId)
                                    : lifecycleService.failAfterDispatch(requestId, orgId));
                    return terminal.then(Mono.error(ex));
                });
    }

    /**
     * Provider credential decryption performs a synchronous MyBatis read, so it
     * runs on the dedicated gateway-db scheduler, never the event loop.
     */
    private Mono<ProviderCallContext> buildProviderContext(
            GatewayPrincipal principal, DispatchResult result) {
        return blockingIo.call(() -> new ProviderCallContext(
                result.adapterCode(),
                result.providerAccountId(),
                result.providerModelId(),
                result.providerModelName(),
                result.pricingVersionId(),
                result.currency(),
                result.baseUrl(),
                "API_KEY",
                "api-key",
                credentialDecryptor.decrypt(principal.organizationId(), result.providerAccountId())));
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
