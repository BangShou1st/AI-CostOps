package com.aicostops.gateway.provider.bridge;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.DispatchEndpointGuard;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.request.ChatCompletionCommand;
import io.github.bangshou1st.modelbridge.core.model.ChatRequest;
import io.github.bangshou1st.modelbridge.core.model.ChatResponse;
import io.github.bangshou1st.modelbridge.core.model.EndpointConfig;
import io.github.bangshou1st.modelbridge.core.model.FinishReason;
import io.github.bangshou1st.modelbridge.core.model.Message;
import io.github.bangshou1st.modelbridge.core.model.ProviderConfig;
import io.github.bangshou1st.modelbridge.core.model.Role;
import io.github.bangshou1st.modelbridge.core.model.StreamChunk;
import io.github.bangshou1st.modelbridge.core.model.TokenUsage;
import io.github.bangshou1st.modelbridge.core.client.StreamHandle;
import io.github.bangshou1st.modelbridge.core.protocol.ModelBridgeClient;
import io.github.bangshou1st.modelbridge.core.protocol.ProtocolId;
import io.github.bangshou1st.modelbridge.core.security.ErrorCategory;
import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.SSLHandshakeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI-compatible Provider adapter backed by ai-model-bridge core.
 *
 * <p>The bridge owns Provider wire semantics (request body, SSE frames, usage
 * extraction and protocol validation). This class owns the AI-CostOps
 * boundary: the frozen call context, route id propagation, bounded streaming,
 * and the financial safety classification required by the failover coordinator.
 */
@Component
public class BridgeOpenAiCompatibleChatAdapter implements ProviderChatAdapter {

    public static final String ADAPTER_CODE = "CUSTOM_OPENAI_COMPATIBLE";

    private static final Logger log =
            LoggerFactory.getLogger(BridgeOpenAiCompatibleChatAdapter.class);
    private static final String DEFAULT_COMPLETION_PATH = "/chat/completions";
    private static final int STREAM_QUEUE_CAPACITY = 256;
    private static final Scheduler WATCHDOG = Schedulers.parallel();

    private final ModelBridgeClient client;
    private final ExecutorService executor;
    private final Scheduler scheduler;
    private final GatewayProperties properties;
    private final DispatchEndpointGuard endpointGuard;

    public BridgeOpenAiCompatibleChatAdapter(
            ModelBridgeClient client,
            @Qualifier("bridgeProviderExecutor") ExecutorService executor,
            GatewayProperties properties,
            DispatchEndpointGuard endpointGuard) {
        this.client = client;
        this.executor = executor;
        this.scheduler = Schedulers.fromExecutor(executor);
        this.properties = properties;
        this.endpointGuard = endpointGuard;
    }

    @Override
    public String adapterCode() {
        return ADAPTER_CODE;
    }

    @Override
    public Mono<ProviderChatCompletion> complete(
            ProviderCallContext context, ChatCompletionCommand command) {
        return Mono.defer(() -> {
                    validateCredentials(context);
                    endpointGuard.check(context.baseUrl(), context.completionPath(),
                            context.networkPolicy());
                    var config = providerConfig(context, false);
                    var request = chatRequest(context, command);
                    return Mono.fromCallable(() -> client.complete(config, request))
                            .subscribeOn(scheduler)
                            .map(response -> toCompletion(response))
                            .onErrorMap(error -> mapFailure(error, false, false));
                });
    }

    @Override
    public Flux<ProviderChatStreamEvent> stream(
            ProviderCallContext context, ChatCompletionCommand command) {
        try {
            validateCredentials(context);
            endpointGuard.check(context.baseUrl(), context.completionPath(),
                    context.networkPolicy());
        } catch (Throwable failure) {
            return Flux.error(mapFailure(failure, false, false));
        }

        Sinks.Many<ProviderChatStreamEvent> sink = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<ProviderChatStreamEvent>(
                        STREAM_QUEUE_CAPACITY));
        var canceled = new AtomicBoolean();
        var emitted = new AtomicBoolean();
        var done = new AtomicBoolean();
        var current = new AtomicReference<StreamHandle>();
        var lastEventNanos = new AtomicLong(System.nanoTime());
        var startedNanos = System.nanoTime();
        var config = providerConfig(context, true);
        var request = chatRequest(context, command);

        var flux = sink.asFlux()
                .doOnCancel(() -> cancel(canceled, current))
                .doOnTerminate(() -> cancel(canceled, current));

        var watchdog = WATCHDOG.schedulePeriodically(() -> {
            if (canceled.get()) {
                return;
            }
            var now = System.nanoTime();
            var idle = TimeUnit.NANOSECONDS.toMillis(now - lastEventNanos.get());
            var elapsed = TimeUnit.NANOSECONDS.toMillis(now - startedNanos);
            if (idle > properties.getStreamIdleTimeoutMs()
                    || elapsed > properties.getHardTimeoutMs()) {
                cancel(canceled, current);
                sink.tryEmitError(new ProviderExecutionException(
                        ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                        ProviderSafetyReason.STREAM_TIMEOUT,
                        ProviderHealthSignal.QUALIFYING_FAILURE,
                        null, null, true, null));
            }
        }, 1, 1, TimeUnit.SECONDS);

        try {
            var handle = client.openStream(config, request, chunk -> {
                if (canceled.get()) {
                    throw new StreamCanceled();
                }
                lastEventNanos.set(System.nanoTime());
                var event = toStreamEvent(chunk);
                if (event == null) {
                    return;
                }
                emitted.set(true);
                if (event instanceof ProviderChatStreamEvent.Done) {
                    done.set(true);
                }
                emit(sink, event, canceled);
            });
            current.set(handle);
            if (canceled.get()) {
                handle.cancel();
            }
            executor.submit(() -> {
                try {
                    handle.await();
                    if (!done.get() && !canceled.get()) {
                        emit(sink, new ProviderChatStreamEvent.Done(), canceled);
                    }
                    if (!canceled.get()) {
                        sink.tryEmitComplete();
                    }
                } catch (Throwable error) {
                    if (!canceled.get()) {
                        sink.tryEmitError(mapFailure(error, true, emitted.get()));
                    }
                } finally {
                    watchdog.dispose();
                }
            });
        } catch (Throwable error) {
            watchdog.dispose();
            if (!canceled.get()) {
                sink.tryEmitError(mapFailure(error, true, emitted.get()));
            }
        }
        return flux;
    }

    private static void emit(
            Sinks.Many<ProviderChatStreamEvent> sink,
            ProviderChatStreamEvent event,
            AtomicBoolean canceled) {
        while (!canceled.get()) {
            var result = sink.tryEmitNext(event);
            if (result == Sinks.EmitResult.OK) {
                return;
            }
            if (result == Sinks.EmitResult.FAIL_OVERFLOW
                    || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                // The bounded queue is full: park the Provider reader so TCP
                // backpressure reaches the upstream instead of growing a heap queue.
                LockSupport.parkNanos(1_000_000L);
                continue;
            }
            throw new StreamCanceled();
        }
        throw new StreamCanceled();
    }

    private static void cancel(AtomicBoolean canceled, AtomicReference<StreamHandle> handle) {
        if (!canceled.compareAndSet(false, true)) {
            return;
        }
        var running = handle.get();
        if (running != null) {
            running.cancel();
        }
    }

    private ProviderConfig providerConfig(ProviderCallContext context, boolean stream) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("X-Client-Request-Id", boundedRouteDecisionId(context.routeDecisionId()));
        final String apiKey;
        if ("NONE".equals(context.credentialType())) {
            apiKey = null;
        } else if ("BEARER_TOKEN".equals(context.credentialType())) {
            apiKey = secret(context);
        } else if ("API_KEY".equals(context.credentialType())) {
            apiKey = null;
            var header = context.authHeaderName() == null || context.authHeaderName().isBlank()
                    ? "X-Api-Key" : context.authHeaderName();
            headers.put(header, secret(context));
        } else {
            throw credentialFailure();
        }
        return new ProviderConfig(
                "account-" + context.providerAccountId() + "-model-" + context.providerModelId(),
                ProtocolId.OPENAI_COMPATIBLE,
                context.baseUrl(),
                apiKey,
                headers,
                Duration.ofMillis(properties.getConnectTimeoutMs()),
                Duration.ofMillis(stream
                        ? properties.getHardTimeoutMs() : properties.getHeaderTimeoutMs()),
                Map.of())
                .withEndpointConfig(EndpointConfig.builder()
                        .chatPath(context.completionPath() == null
                                || context.completionPath().isBlank()
                                ? DEFAULT_COMPLETION_PATH : context.completionPath())
                        .build());
    }

    private static ChatRequest chatRequest(
            ProviderCallContext context, ChatCompletionCommand command) {
        var messages = new ArrayList<Message>();
        for (var message : command.messages()) {
            messages.add(new Message(role(message.role()), message.content(),
                    null, null, List.of(), null));
        }
        return ChatRequest.builder(context.providerModelName())
                .messages(messages)
                .maxOutputTokens(command.maxCompletionTokens())
                .requestId(boundedRouteDecisionId(context.routeDecisionId()))
                .failOnLength(false)
                .build();
    }

    private static Role role(String role) {
        if (role == null) {
            return Role.USER;
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "system" -> Role.SYSTEM;
            case "assistant" -> Role.ASSISTANT;
            case "tool" -> Role.TOOL;
            default -> Role.USER;
        };
    }

    private static ProviderChatCompletion toCompletion(ChatResponse response) {
        var choices = List.of(new ProviderChatCompletion.CompletionChoice(
                0,
                response.content(),
                finishReason(response.finishReason())));
        var usage = response.usage();
        return new ProviderChatCompletion(
                null,
                null,
                System.currentTimeMillis() / 1000L,
                response.model(),
                choices,
                usage == null || usage.isEmpty() ? null
                        : new ProviderChatCompletion.ProviderUsage(
                                usage.inputTokens(), usage.outputTokens(), usage.totalTokens()));
    }

    private static ProviderChatStreamEvent toStreamEvent(StreamChunk chunk) {
        return switch (chunk.type()) {
            case CONTENT -> new ProviderChatStreamEvent.Delta(
                    null, System.currentTimeMillis() / 1000L, chunk.model(), chunk.delta());
            case USAGE -> {
                var usage = chunk.usage();
                if (usage == null || usage.isEmpty()) {
                    yield null;
                }
                yield new ProviderChatStreamEvent.Metering(
                        null, System.currentTimeMillis() / 1000L, chunk.model(),
                        usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
            }
            case DONE -> new ProviderChatStreamEvent.Done();
            case REASONING, TOOL_CALL_DELTA -> null;
        };
    }

    private static String finishReason(FinishReason reason) {
        return reason == null ? null : reason.name().toLowerCase(Locale.ROOT);
    }

    private void validateCredentials(ProviderCallContext context) {
        if ("NONE".equals(context.credentialType())) {
            return;
        }
        if (context.providerSecret() == null
                || (!"BEARER_TOKEN".equals(context.credentialType())
                        && !"API_KEY".equals(context.credentialType()))) {
            throw credentialFailure();
        }
    }

    private static String secret(ProviderCallContext context) {
        return new String(context.providerSecret(), StandardCharsets.UTF_8);
    }

    private static String boundedRouteDecisionId(String routeDecisionId) {
        if (routeDecisionId == null || routeDecisionId.isBlank()) {
            return "unknown-route";
        }
        var trimmed = routeDecisionId.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private static ProviderExecutionException credentialFailure() {
        return new ProviderExecutionException(
                ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                ProviderSafetyReason.UNKNOWN_POST_DISPATCH,
                ProviderHealthSignal.QUALIFYING_FAILURE,
                null, null, false, null);
    }

    private static ProviderExecutionException mapFailure(
            Throwable error, boolean stream, boolean emitted) {
        if (error instanceof ProviderExecutionException providerFailure) {
            return providerFailure;
        }
        if (error instanceof StreamCanceled) {
            return new ProviderExecutionException(
                    ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.CLIENT_CANCEL_AFTER_DISPATCH,
                    ProviderHealthSignal.CLIENT_CANCELLATION,
                    null, null, true, null);
        }
        if (error instanceof ModelBridgeException bridge) {
            return mapBridgeFailure(bridge, emitted);
        }
        if (hasCause(error, UnknownHostException.class)) {
            return safe(ProviderSafetyReason.DNS_PRE_CONNECT, error);
        }
        if (hasCause(error, HttpConnectTimeoutException.class)
                || hasCause(error, java.net.ConnectException.class)) {
            return safe(ProviderSafetyReason.CONNECT_TIMEOUT_PRE_WRITE, error);
        }
        if (hasCause(error, SSLHandshakeException.class)) {
            return safe(ProviderSafetyReason.TLS_HANDSHAKE_PRE_HTTP_WRITE, error);
        }
        if (hasCause(error, SocketException.class)) {
            return billable(ProviderSafetyReason.CONNECTION_RESET_WRITE_POSSIBLE,
                    null, true, error);
        }
        return billable(ProviderSafetyReason.UNKNOWN_POST_DISPATCH, null, stream || emitted, error);
    }

    private static ProviderExecutionException mapBridgeFailure(
            ModelBridgeException failure, boolean emitted) {
        var status = failure.upstreamStatus() > 0 ? failure.upstreamStatus() : null;
        var category = failure.category();
        if (category == ErrorCategory.NETWORK_POLICY) {
            return safe(ProviderSafetyReason.ENDPOINT_BLOCKED_PRE_DISPATCH, failure);
        }
        if (category == ErrorCategory.AUTHENTICATION
                || category == ErrorCategory.AUTHORIZATION
                || category == ErrorCategory.BAD_REQUEST
                || category == ErrorCategory.RATE_LIMIT
                || (category == ErrorCategory.UNAVAILABLE && status != null)) {
            return new ProviderExecutionException(
                    ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.HTTP_RESPONSE_RECEIVED,
                    healthSignal(status),
                    status, null, status != null || emitted, failure);
        }
        if (category == ErrorCategory.TIMEOUT) {
            if (hasCause(failure, HttpConnectTimeoutException.class)
                    || hasCause(failure, ConnectException.class)) {
                return safe(ProviderSafetyReason.CONNECT_TIMEOUT_PRE_WRITE, failure);
            }
            if (hasCause(failure, UnknownHostException.class)) {
                return safe(ProviderSafetyReason.DNS_PRE_CONNECT, failure);
            }
            if (hasCause(failure, SSLHandshakeException.class)) {
                return safe(ProviderSafetyReason.TLS_HANDSHAKE_PRE_HTTP_WRITE, failure);
            }
            return billable(ProviderSafetyReason.HEADER_TIMEOUT_WRITE_POSSIBLE,
                    status, emitted, failure);
        }
        if (category == ErrorCategory.INVALID_RESPONSE
                || category == ErrorCategory.LENGTH
                || category == ErrorCategory.SCHEMA) {
            return billable(ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE,
                    status, true, failure);
        }
        if (hasCause(failure, UnknownHostException.class)) {
            return safe(ProviderSafetyReason.DNS_PRE_CONNECT, failure);
        }
        if (hasCause(failure, ConnectException.class)) {
            return safe(ProviderSafetyReason.CONNECT_REFUSED_PRE_WRITE, failure);
        }
        if (hasCause(failure, SSLHandshakeException.class)) {
            return safe(ProviderSafetyReason.TLS_HANDSHAKE_PRE_HTTP_WRITE, failure);
        }
        if (hasCause(failure, SocketException.class)) {
            return billable(ProviderSafetyReason.CONNECTION_RESET_WRITE_POSSIBLE,
                    status, true, failure);
        }
        return billable(ProviderSafetyReason.UNKNOWN_POST_DISPATCH, status, emitted, failure);
    }

    private static ProviderHealthSignal healthSignal(Integer status) {
        if (status == null) {
            return ProviderHealthSignal.QUALIFYING_FAILURE;
        }
        return status == 401 || status == 403 || status == 404
                ? ProviderHealthSignal.ROUTE_CONFIGURATION_FAILURE
                : status >= 400 && status < 500 && status != 429
                        ? ProviderHealthSignal.NONE
                        : ProviderHealthSignal.QUALIFYING_FAILURE;
    }

    private static ProviderExecutionException safe(
            ProviderSafetyReason reason, Throwable cause) {
        return new ProviderExecutionException(
                ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                reason, ProviderHealthSignal.QUALIFYING_FAILURE,
                null, null, false, cause);
    }

    private static ProviderExecutionException billable(
            ProviderSafetyReason reason, Integer status, boolean responseStarted, Throwable cause) {
        return new ProviderExecutionException(
                ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                reason, healthSignal(status),
                status, null, responseStarted, cause);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class StreamCanceled extends RuntimeException {
    }
}
