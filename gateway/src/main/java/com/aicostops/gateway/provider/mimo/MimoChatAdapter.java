package com.aicostops.gateway.provider.mimo;

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
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.ObjectMapper;

/**
 * MiMo OpenAI-compatible Chat Completions adapter. Only server-governed
 * destinations are used; the Provider secret is injected per request and
 * Provider error bodies are redacted. Never retries after a committed
 * DISPATCH_INTENT. Streaming parses/increments the upstream SSE without ever
 * aggregating the full completion, using configured connect/header/idle/hard
 * timeouts and no automatic retry.
 */
@Component
public class MimoChatAdapter implements ProviderChatAdapter {

    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    private static final String DONE_MARKER = "[DONE]";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final boolean enforceProductionEndpoint;

    public MimoChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            Environment environment) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.enforceProductionEndpoint = environment.acceptsProfiles(Profiles.of("prod"));
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getHeaderTimeoutMs()));
        this.webClient = builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(properties.getMaxInMemoryBytes()))
                .build();
    }

    @Override
    public String adapterCode() {
        return "MIMO";
    }

    @Override
    public Mono<ProviderChatCompletion> complete(
            ProviderCallContext context, ChatCompletionCommand command) {
        if (enforceProductionEndpoint) {
            MimoEndpointPolicy.validate(context.baseUrl());
        }
        if (!"API_KEY".equals(context.credentialType())) {
            return Mono.error(new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, null));
        }
        var wireRequest = new MimoWireDtos.WireRequest(
                context.providerModelName(),
                command.messages().stream()
                        .map(message -> new MimoWireDtos.WireMessage(message.role(), message.content()))
                        .toList(),
                command.maxCompletionTokens(),
                false);
        return webClient.post()
                .uri(context.baseUrl() + CHAT_COMPLETIONS_SUFFIX)
                .header("api-key",
                        new String(context.providerSecret(), StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wireRequest)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(byte[].class)
                                .map(body -> parseCompletion(context, body, providerRequestId(response)));
                    }
                    // Bounded read then redact: never return the arbitrary body.
                    return response.bodyToMono(byte[].class)
                            .flatMap(body -> Mono.<ProviderChatCompletion>error(new ProviderExecutionException(
                                    ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                                    ProviderSafetyReason.HTTP_RESPONSE_RECEIVED,
                                    ProviderHealthSignal.QUALIFYING_FAILURE,
                                    response.statusCode().value(),
                                    providerRequestId(response), true, null)));
                })
                .onErrorResume(ex -> Mono.error(mapTransportError(ex)));
    }

    @Override
    public Flux<ProviderChatStreamEvent> stream(
            ProviderCallContext context, ChatCompletionCommand command) {
        if (enforceProductionEndpoint) {
            MimoEndpointPolicy.validate(context.baseUrl());
        }
        if (!"API_KEY".equals(context.credentialType())) {
            return Flux.error(new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, null));
        }
        var wireRequest = new MimoWireDtos.WireRequest(
                context.providerModelName(),
                command.messages().stream()
                        .map(message -> new MimoWireDtos.WireMessage(message.role(), message.content()))
                        .toList(),
                command.maxCompletionTokens(),
                true);
        var decoder = new MimoSseDecoder(properties.getMaxInMemoryBytes());
        var hardTimeoutMs = properties.getHardTimeoutMs();
        return Flux.defer(() -> {
            var startNanos = System.nanoTime();
            return webClient.post()
                    .uri(context.baseUrl() + CHAT_COMPLETIONS_SUFFIX)
                    .header("api-key",
                            new String(context.providerSecret(), StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(wireRequest)
                    .exchangeToFlux(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(byte[].class)
                                    .flatMapMany(body -> Flux.error(new ProviderExecutionException(
                                            ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                                            ProviderSafetyReason.HTTP_RESPONSE_RECEIVED,
                                            ProviderHealthSignal.QUALIFYING_FAILURE,
                                            response.statusCode().value(),
                                            providerRequestId(response), true, null)));
                        }
                        return response.bodyToFlux(DataBuffer.class)
                                .concatMap(buffer -> decodeEvents(decoder, buffer));
                    })
                    // Stream idle timeout: maximum interval between upstream events.
                    .timeout(Duration.ofMillis(properties.getStreamIdleTimeoutMs()))
                    // Hard deadline: maximum wall-clock lifetime of the whole stream,
                    // checked as events keep arriving so a slow-but-active stream
                    // cannot run past the configured deadline.
                    .map(chunk -> enforceHardDeadline(chunk, startNanos, hardTimeoutMs))
                    .onErrorResume(ex -> Flux.error(mapTransportError(ex)));
        });
    }

    private static ProviderChatStreamEvent enforceHardDeadline(
            ProviderChatStreamEvent event, long startNanos, int hardTimeoutMs) {
        if (System.nanoTime() - startNanos > hardTimeoutMs * 1_000_000L) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT,
                    "Provider stream hard deadline exceeded");
        }
        return event;
    }

    private Flux<ProviderChatStreamEvent> decodeEvents(MimoSseDecoder decoder, DataBuffer buffer) {
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return Flux.fromIterable(decoder.feed(bytes)).map(this::parseChunk);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private ProviderChatStreamEvent parseChunk(String payload) {
        if (DONE_MARKER.equals(payload)) {
            return new ProviderChatStreamEvent.Done();
        }
        try {
            var wire = objectMapper.readValue(payload, MimoWireDtos.WireChunk.class);
            var created = wire.created() == null ? 0L : wire.created();
            if (wire.usage() != null) {
                return new ProviderChatStreamEvent.Metering(
                        wire.id(), created, wire.model(),
                        wire.usage().prompt_tokens(),
                        wire.usage().completion_tokens(),
                        wire.usage().total_tokens());
            }
            var choice = wire.choices() == null || wire.choices().isEmpty()
                    ? null : wire.choices().get(0);
            var content = choice == null || choice.delta() == null
                    ? null : choice.delta().content();
            return new ProviderChatStreamEvent.Delta(wire.id(), created, wire.model(), content);
        } catch (Exception ex) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE,
                    ProviderHealthSignal.QUALIFYING_FAILURE, null, null, true, ex);
        }
    }

    private ProviderChatCompletion parseCompletion(ProviderCallContext context, byte[] body, String providerRequestId) {
        try {
            var wire = objectMapper.readValue(body, MimoWireDtos.WireResponse.class);
            var choices = new ArrayList<ProviderChatCompletion.CompletionChoice>();
            if (wire.choices() != null) {
                for (var choice : wire.choices()) {
                    choices.add(new ProviderChatCompletion.CompletionChoice(
                            choice.index() == null ? 0 : choice.index(),
                            choice.message() == null ? null : choice.message().content(),
                            choice.finish_reason()));
                }
            }
            ProviderChatCompletion.ProviderUsage usage = null;
            if (wire.usage() != null) {
                usage = new ProviderChatCompletion.ProviderUsage(
                        wire.usage().prompt_tokens(),
                        wire.usage().completion_tokens(),
                        wire.usage().total_tokens());
            }
            return new ProviderChatCompletion(
                    providerRequestId,
                    wire.id(),
                    wire.created() == null ? 0L : wire.created(),
                    wire.model() == null ? context.providerModelName() : wire.model(),
                    choices,
                    usage);
        } catch (Exception ex) {
            throw new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.MALFORMED_PROVIDER_RESPONSE,
                    ProviderHealthSignal.QUALIFYING_FAILURE, null, null, true, ex);
        }
    }

    private static GatewayErrorException mapTransportError(Throwable ex) {
        if (ex instanceof ProviderExecutionException providerError) {
            return providerError;
        }
        if (ex instanceof GatewayErrorException gatewayError) {
            return gatewayError;
        }
        var root = rootCause(ex);
        if (root instanceof java.net.UnknownHostException) {
            return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.DNS_PRE_CONNECT, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, ex);
        }
        if (root instanceof io.netty.channel.ConnectTimeoutException) {
            return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.CONNECT_TIMEOUT_PRE_WRITE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, ex);
        }
        if (root instanceof java.net.ConnectException) {
            return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.CONNECT_REFUSED_PRE_WRITE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, ex);
        }
        if (root instanceof javax.net.ssl.SSLHandshakeException) {
            return new ProviderExecutionException(ProviderSafetyOutcome.SAFE_NO_BILLABLE_EXECUTION,
                    ProviderSafetyReason.TLS_HANDSHAKE_PRE_HTTP_WRITE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, ex);
        }
        if (isTimeout(ex)) {
            return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.HEADER_TIMEOUT_WRITE_POSSIBLE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, false, ex);
        }
        if (root instanceof java.net.SocketException) {
            return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                    ProviderSafetyReason.CONNECTION_RESET_WRITE_POSSIBLE, ProviderHealthSignal.QUALIFYING_FAILURE,
                    null, null, true, ex);
        }
        return new ProviderExecutionException(ProviderSafetyOutcome.BILLABLE_POSSIBLE,
                ProviderSafetyReason.UNKNOWN_POST_DISPATCH, ProviderHealthSignal.QUALIFYING_FAILURE,
                null, null, true, ex);
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable current = reactor.core.Exceptions.unwrap(ex);
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static String providerRequestId(org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.headers().asHttpHeaders().getFirst("x-request-id");
    }

    private static boolean isTimeout(Throwable ex) {
        var unwrapped = reactor.core.Exceptions.unwrap(ex);
        if (unwrapped instanceof TimeoutException
                || unwrapped instanceof java.net.SocketTimeoutException
                || unwrapped instanceof io.netty.handler.timeout.ReadTimeoutException
                || unwrapped instanceof java.io.InterruptedIOException) {
            return true;
        }
        for (Throwable current = unwrapped.getCause();
                current != null && current != current.getCause();
                current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException
                    || current instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
