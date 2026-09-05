package com.aicostops.gateway.provider.openai;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.provider.ProviderChatStreamEvent;
import com.aicostops.gateway.provider.ProviderExecutionException;
import com.aicostops.gateway.provider.ProviderHealthSignal;
import com.aicostops.gateway.provider.ProviderSafetyOutcome;
import com.aicostops.gateway.provider.ProviderSafetyReason;
import com.aicostops.gateway.request.ChatCompletionCommand;
import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;

/** OpenAI Chat Completions adapter with non-stream and include-usage SSE support. */
@Component
public class OpenAiChatAdapter implements ProviderChatAdapter {

    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    private static final String DONE_MARKER = "[DONE]";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final boolean enforceProductionEndpoint;

    public OpenAiChatAdapter(WebClient.Builder builder, ObjectMapper objectMapper,
            GatewayProperties properties, Environment environment) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.enforceProductionEndpoint = environment.acceptsProfiles(Profiles.of("prod"));
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getHeaderTimeoutMs()));
        this.webClient = builder.clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(properties.getMaxInMemoryBytes()))
                .build();
    }

    @Override
    public String adapterCode() {
        return "OPENAI";
    }

    @Override
    public Mono<ProviderChatCompletion> complete(ProviderCallContext context,
            ChatCompletionCommand command) {
        validateContext(context);
        var request = wireRequest(context, command, false);
        return webClient.post().uri(context.baseUrl() + CHAT_COMPLETIONS_SUFFIX)
                .header("Authorization", "Bearer " + new String(context.providerSecret(), StandardCharsets.UTF_8))
                .header("X-Client-Request-Id", boundedRouteDecisionId(context.routeDecisionId()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(byte[].class)
                                .map(body -> parseCompletion(context, body, providerRequestId(response)));
                    }
                    return response.bodyToMono(byte[].class)
                            .flatMap(body -> Mono.<ProviderChatCompletion>error(httpFailure(response)));
                }).onErrorResume(ex -> Mono.error(mapTransportError(ex)));
    }

    @Override
    public Flux<ProviderChatStreamEvent> stream(ProviderCallContext context,
            ChatCompletionCommand command) {
        validateContext(context);
        var request = wireRequest(context, command, true);
        var decoder = new OpenAiSseDecoder(properties.getMaxInMemoryBytes());
        var hardTimeoutMs = properties.getHardTimeoutMs();
        return Flux.defer(() -> {
            var startNanos = System.nanoTime();
            return webClient.post().uri(context.baseUrl() + CHAT_COMPLETIONS_SUFFIX)
                    .header("Authorization", "Bearer " + new String(context.providerSecret(), StandardCharsets.UTF_8))
                    .header("X-Client-Request-Id", boundedRouteDecisionId(context.routeDecisionId()))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                    .exchangeToFlux(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(byte[].class)
                                    .flatMapMany(body -> Flux.error(httpFailure(response)));
                        }
                        return response.bodyToFlux(DataBuffer.class)
                                .concatMap(buffer -> decodeEvents(decoder, buffer));
                    }).timeout(Duration.ofMillis(properties.getStreamIdleTimeoutMs()))
                    .map(event -> enforceHardDeadline(event, startNanos, hardTimeoutMs))
                    .onErrorResume(ex -> Flux.error(mapTransportError(ex)));
        });
    }

    private Object wireRequest(ProviderCallContext context,
            ChatCompletionCommand command, boolean stream) {
        var messages = command.messages().stream().map(message -> new OpenAiWireDtos.WireMessage(
                message.role(), message.content())).toList();
        if (stream) {
            return new OpenAiWireDtos.StreamingWireRequest(context.providerModelName(), messages,
                    command.maxCompletionTokens(), true, new OpenAiWireDtos.StreamOptions(true));
        }
        return new OpenAiWireDtos.WireRequest(context.providerModelName(), messages,
                command.maxCompletionTokens(), false);
    }

    private void validateContext(ProviderCallContext context) {
        if (!"BEARER_TOKEN".equals(context.credentialType())) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, null);
        }
        if (enforceProductionEndpoint) OpenAiEndpointPolicy.validate(context.baseUrl());
    }

    private Flux<ProviderChatStreamEvent> decodeEvents(OpenAiSseDecoder decoder, DataBuffer buffer) {
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return Flux.fromIterable(decoder.feed(bytes)).map(this::parseChunk);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private ProviderChatStreamEvent parseChunk(String payload) {
        if (DONE_MARKER.equals(payload)) return new ProviderChatStreamEvent.Done();
        try {
            var wire = objectMapper.readValue(payload, OpenAiWireDtos.WireChunk.class);
            var created = wire.created() == null ? 0L : wire.created();
            if (wire.usage() != null) {
                return new ProviderChatStreamEvent.Metering(wire.id(), created, wire.model(),
                        wire.usage().prompt_tokens(), wire.usage().completion_tokens(), wire.usage().total_tokens());
            }
            var choice = wire.choices() == null || wire.choices().isEmpty() ? null : wire.choices().get(0);
            var content = choice == null || choice.delta() == null ? null : choice.delta().content();
            return new ProviderChatStreamEvent.Delta(wire.id(), created, wire.model(), content);
        } catch (Exception ex) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, true, ex);
        }
    }

    private ProviderChatCompletion parseCompletion(ProviderCallContext context, byte[] body,
            String requestId) {
        try {
            var wire = objectMapper.readValue(body, OpenAiWireDtos.WireResponse.class);
            var choices = new ArrayList<ProviderChatCompletion.CompletionChoice>();
            if (wire.choices() != null) {
                for (var choice : wire.choices()) {
                    choices.add(new ProviderChatCompletion.CompletionChoice(
                            choice.index() == null ? 0 : choice.index(),
                            choice.message() == null ? null : choice.message().content(),
                            choice.finish_reason()));
                }
            }
            var usage = wire.usage() == null ? null : new ProviderChatCompletion.ProviderUsage(
                    wire.usage().prompt_tokens(), wire.usage().completion_tokens(), wire.usage().total_tokens());
            return new ProviderChatCompletion(requestId, wire.id(), wire.created() == null ? 0 : wire.created(),
                    wire.model() == null ? context.providerModelName() : wire.model(), choices, usage);
        } catch (Exception ex) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, requestId, true, ex);
        }
    }

    private ProviderExecutionException httpFailure(ClientResponse response) {
        return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                ProviderSafetyReason.HTTP_RESPONSE_RECEIVED, healthSignal(response.statusCode().value()),
                response.statusCode().value(), providerRequestId(response), true, null);
    }

    private static ProviderHealthSignal healthSignal(int status) {
        return status == 401 || status == 403 || status == 404
                ? ProviderHealthSignal.ROUTE_CONFIGURATION_FAILURE
                : status >= 400 && status < 500 && status != 429
                        ? ProviderHealthSignal.NONE : ProviderHealthSignal.QUALIFYING_FAILURE;
    }

    private static ProviderChatStreamEvent enforceHardDeadline(ProviderChatStreamEvent event,
            long startNanos, int hardTimeoutMs) {
        if (System.nanoTime() - startNanos > hardTimeoutMs * 1_000_000L) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.STREAM_TIMEOUT, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, true, null);
        }
        return event;
    }

    private static String providerRequestId(ClientResponse response) {
        return response.headers().asHttpHeaders().getFirst("x-request-id");
    }

    private static String boundedRouteDecisionId(String routeDecisionId) {
        if (routeDecisionId == null || routeDecisionId.isBlank()) return "unknown-route";
        var trimmed = routeDecisionId.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private static ProviderExecutionException mapTransportError(Throwable ex) {
        if (ex instanceof ProviderExecutionException providerError) return providerError;
        if (hasCause(ex, java.net.UnknownHostException.class)) return safe(ProviderSafetyReason.DNS_PRE_CONNECT, ex);
        if (hasCause(ex, io.netty.channel.ConnectTimeoutException.class)) return safe(ProviderSafetyReason.CONNECT_TIMEOUT_PRE_WRITE, ex);
        if (hasCause(ex, java.net.ConnectException.class)) return safe(ProviderSafetyReason.CONNECT_REFUSED_PRE_WRITE, ex);
        if (hasCause(ex, javax.net.ssl.SSLHandshakeException.class)) return safe(ProviderSafetyReason.TLS_HANDSHAKE_PRE_HTTP_WRITE, ex);
        if (isTimeout(ex)) return billable(ProviderSafetyReason.HEADER_TIMEOUT_WRITE_POSSIBLE, ex, false);
        if (hasCause(ex, java.net.SocketException.class)) return billable(ProviderSafetyReason.CONNECTION_RESET_WRITE_POSSIBLE, ex, true);
        return billable(ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ex, true);
    }

    private static ProviderExecutionException safe(ProviderSafetyReason reason, Throwable cause) {
        return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION, reason,
                ProviderHealthSignal.QUALIFYING_FAILURE, null, null, false, cause);
    }

    private static ProviderExecutionException billable(ProviderSafetyReason reason, Throwable cause,
            boolean responseStarted) {
        return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE, reason,
                ProviderHealthSignal.QUALIFYING_FAILURE, null, null, responseStarted, cause);
    }

    private static Throwable rootCause(Throwable ex) {
        var current = reactor.core.Exceptions.unwrap(ex);
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (var current = reactor.core.Exceptions.unwrap(error); current != null;
                current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static boolean isTimeout(Throwable ex) {
        var current = reactor.core.Exceptions.unwrap(ex);
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof java.net.SocketTimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException
                    || current instanceof java.io.InterruptedIOException) return true;
            current = current.getCause();
        }
        return false;
    }
}
