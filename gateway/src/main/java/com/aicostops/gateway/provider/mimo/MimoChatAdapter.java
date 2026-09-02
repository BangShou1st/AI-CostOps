package com.aicostops.gateway.provider.mimo;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.provider.ProviderCallContext;
import com.aicostops.gateway.provider.ProviderChatAdapter;
import com.aicostops.gateway.provider.ProviderChatChunk;
import com.aicostops.gateway.provider.ProviderChatCompletion;
import com.aicostops.gateway.request.ChatCompletionCommand;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
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

    public MimoChatAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            GatewayProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
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
    public Mono<ProviderChatCompletion> complete(
            ProviderCallContext context, ChatCompletionCommand command) {
        var wireRequest = new MimoWireDtos.WireRequest(
                context.providerModelName(),
                command.messages().stream()
                        .map(message -> new MimoWireDtos.WireMessage(message.role(), message.content()))
                        .toList(),
                command.maxCompletionTokens(),
                false);
        return webClient.post()
                .uri(context.baseUrl() + CHAT_COMPLETIONS_SUFFIX)
                .header(context.providerKeyHeader(),
                        new String(context.providerSecret(), StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wireRequest)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(byte[].class)
                                .map(body -> parseCompletion(context, body));
                    }
                    // Bounded read then redact: never return the arbitrary body.
                    return response.bodyToMono(byte[].class)
                            .flatMap(body -> Mono.error(new GatewayErrorException(
                                    GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                                    "Provider request failed with HTTP "
                                            + response.statusCode().value())));
                })
                .onErrorResume(ex -> Mono.error(mapTransportError(ex)));
    }

    @Override
    public Flux<ProviderChatChunk> stream(
            ProviderCallContext context, ChatCompletionCommand command) {
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
                    .header(context.providerKeyHeader(),
                            new String(context.providerSecret(), StandardCharsets.UTF_8))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(wireRequest)
                    .exchangeToFlux(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(byte[].class)
                                    .flatMapMany(body -> Flux.error(new GatewayErrorException(
                                            GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                                            "Provider request failed with HTTP "
                                                    + response.statusCode().value())));
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

    private static ProviderChatChunk enforceHardDeadline(
            ProviderChatChunk chunk, long startNanos, int hardTimeoutMs) {
        if (System.nanoTime() - startNanos > hardTimeoutMs * 1_000_000L) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT,
                    "Provider stream hard deadline exceeded");
        }
        return chunk;
    }

    private Flux<ProviderChatChunk> decodeEvents(MimoSseDecoder decoder, DataBuffer buffer) {
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return Flux.fromIterable(decoder.feed(bytes)).map(this::parseChunk);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private ProviderChatChunk parseChunk(String payload) {
        if (DONE_MARKER.equals(payload)) {
            return new ProviderChatChunk(null, 0L, null, null, true);
        }
        try {
            var wire = objectMapper.readValue(payload, MimoWireDtos.WireChunk.class);
            var choice = wire.choices() == null || wire.choices().isEmpty()
                    ? null : wire.choices().get(0);
            var content = choice == null || choice.delta() == null
                    ? null : choice.delta().content();
            return new ProviderChatChunk(
                    wire.id(),
                    wire.created() == null ? 0L : wire.created(),
                    wire.model(),
                    content,
                    false);
        } catch (Exception ex) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                    "Provider returned a malformed stream event");
        }
    }

    private ProviderChatCompletion parseCompletion(ProviderCallContext context, byte[] body) {
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
                    null,
                    wire.id(),
                    wire.created() == null ? 0L : wire.created(),
                    wire.model() == null ? context.providerModelName() : wire.model(),
                    choices,
                    usage);
        } catch (Exception ex) {
            throw new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                    "Provider returned an unparseable response");
        }
    }

    private static GatewayErrorException mapTransportError(Throwable ex) {
        if (ex instanceof GatewayErrorException gatewayError) {
            return gatewayError;
        }
        if (isTimeout(ex)) {
            return new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT,
                    "Provider response-header timeout");
        }
        return new GatewayErrorException(GatewayErrorCode.GATEWAY_UPSTREAM_FAILED,
                "Provider request could not be completed");
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