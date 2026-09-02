package com.aicostops.gateway.web;

import com.aicostops.gateway.auth.GatewayBearerWebFilter;
import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.persistence.GatewayReadMapper;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderCredentialDecryptor;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferUtils;
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
    private final GatewaySseEncoder sseEncoder;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxRequestBytes;

    public ChatCompletionController(
            GatewayReadMapper readMapper,
            GatewayRequestService requestService,
            GatewayRequestLifecycleService lifecycleService,
            StreamingLifecycleService streamingLifecycle,
            ProviderCredentialDecryptor credentialDecryptor,
            ProviderChatAdapter chatAdapter,
            GatewaySseEncoder sseEncoder,
            ObjectMapper objectMapper,
            com.aicostops.gateway.config.GatewayProperties properties,
            Clock clock) {
        this.readMapper = readMapper;
        this.requestService = requestService;
        this.lifecycleService = lifecycleService;
        this.streamingLifecycle = streamingLifecycle;
        this.credentialDecryptor = credentialDecryptor;
        this.chatAdapter = chatAdapter;
        this.sseEncoder = sseEncoder;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxRequestBytes = properties.getMaxRequestBytes();
    }

    @PostMapping(path = "/chat/completions", consumes = "application/json")
    public Mono<ResponseEntity<?>> createChatCompletion(
            ServerWebExchange exchange) {
        return principal(exchange)
                .flatMap(principal -> readBoundedBody(exchange)
                        .flatMap(rawBody -> handleRequest(exchange, principal, rawBody)));
    }

    private Mono<ResponseEntity<?>> handleRequest(
            ServerWebExchange exchange, GatewayPrincipal principal, byte[] rawBody) {
        var request = ChatCompletionRequestParser.parse(rawBody, objectMapper);
        var idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                    "Idempotency-Key header is required for billable requests");
        }
        var modelId = readMapper.findModelIdByKey(request.model());
        if (modelId == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                    "Unknown model");
        }
        var model = readMapper.findModelById(modelId);
        var effectiveMaxTokens = resolveEffectiveMaxTokens(request, model);

        return requestService.authorizeAndFence(new AuthorizeCommand(
                        principal, modelId, rawBody, idempotencyKey))
                .flatMap(result -> request.stream()
                        ? Mono.just(invokeStream(exchange, principal, result, request,
                                effectiveMaxTokens))
                        : invokeProvider(exchange, principal, result, request, effectiveMaxTokens)
                                .map(completion -> ResponseEntity.ok(
                                        buildResponse(exchange, principal, result, request,
                                                completion))));
    }

    private ResponseEntity<Flux<ServerSentEvent<String>>> invokeStream(
            ServerWebExchange exchange, GatewayPrincipal principal, DispatchResult result,
            ChatCompletionRequest request, int effectiveMaxTokens) {
        var context = buildProviderContext(principal, result);
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
        Flux<ServerSentEvent<String>> body = lifecycleService
                .beginUpstream(requestId, orgId, result.routeAttemptId())
                .thenMany(chatAdapter.stream(context, command))
                .takeWhile(chunk -> !chunk.done())
                .map(chunk -> ServerSentEvent.<String>builder()
                        // Leading space produces the OpenAI-compatible "data: " prefix:
                        // Spring's SSE writer writes "data:" + value without a space.
                        .data(" " + sseEncoder.encodeChunk(chunk, request.model(), fallbackId))
                        .build())
                .concatWith(Flux.defer(() -> Flux.just(ServerSentEvent.<String>builder()
                        .data(" " + GatewaySseEncoder.DONE_PAYLOAD)
                        .build())))
                .concatWith(Flux.defer(() -> lifecycleService
                        .completeSuccess(requestId, orgId, result.routeAttemptId())
                        .thenMany(Flux.empty())))
                .onErrorResume(ex -> {
                    var terminal = isTimeout(ex)
                            ? streamingLifecycle.timeoutAfterDispatch(requestId, orgId)
                            : lifecycleService.failAfterDispatch(requestId, orgId);
                    return terminal.then(Mono.error(ex));
                })
                .doOnCancel(() -> streamingLifecycle
                        .cancelAfterDispatch(requestId, orgId)
                        .subscribe());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private Mono<ProviderChatCompletion> invokeProvider(
            ServerWebExchange exchange, GatewayPrincipal principal, DispatchResult result,
            ChatCompletionRequest request, int effectiveMaxTokens) {
        var context = buildProviderContext(principal, result);
        var command = new ChatCompletionCommand(
                request.model(),
                request.messages().stream()
                        .map(message -> new ChatCompletionCommand.Message(
                                message.role(), message.content()))
                        .toList(),
                effectiveMaxTokens,
                false);
        setCorrelationHeaders(exchange, result.publicRequestId());
        var requestId = result.requestId();
        var orgId = principal.organizationId();
        return lifecycleService.beginUpstream(requestId, orgId, result.routeAttemptId())
                .then(chatAdapter.complete(context, command))
                .flatMap(completion -> lifecycleService
                        .completeSuccess(requestId, orgId, result.routeAttemptId())
                        .thenReturn(completion))
                .onErrorResume(ex -> {
                    var terminal = isTimeout(ex)
                            ? streamingLifecycle.timeoutAfterDispatch(requestId, orgId)
                            : lifecycleService.failAfterDispatch(requestId, orgId);
                    return terminal.then(Mono.error(ex));
                });
    }

    private ProviderCallContext buildProviderContext(GatewayPrincipal principal, DispatchResult result) {
        return new ProviderCallContext(
                result.adapterCode(),
                result.providerAccountId(),
                result.providerModelId(),
                result.providerModelName(),
                result.pricingVersionId(),
                result.currency(),
                result.baseUrl(),
                "API_KEY",
                "api-key",
                credentialDecryptor.decrypt(principal.organizationId(), result.providerAccountId()));
    }

    private Map<String, Object> buildResponse(ServerWebExchange exchange,
            GatewayPrincipal principal, DispatchResult result, ChatCompletionRequest request,
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
            GatewayReadMapper.ModelRow model) {
        if (model == null) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_INVALID,
                    "Unknown model");
        }
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
        if (contentLength(request) > maxRequestBytes) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_TOO_LARGE,
                    "Request body exceeds the 1 MiB limit");
        }
        return DataBufferUtils.join(request.getBody(), maxRequestBytes + 1)
                .map(buffer -> {
                    try {
                        var bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorMap(ex -> ex instanceof org.springframework.core.io.buffer.DataBufferLimitException
                        ? new GatewayErrorException(GatewayErrorCode.GATEWAY_REQUEST_TOO_LARGE,
                        "Request body exceeds the 1 MiB limit")
                        : ex);
    }

    private static long contentLength(ServerHttpRequest request) {
        var length = request.getHeaders().getContentLength();
        return length >= 0 ? length : Long.MAX_VALUE;
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
        var existing = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        return existing == null || existing.isBlank() ? "trc_" + UUID.randomUUID() : existing;
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
}