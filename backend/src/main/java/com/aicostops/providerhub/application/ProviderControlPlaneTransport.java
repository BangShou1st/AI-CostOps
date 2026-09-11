package com.aicostops.providerhub.application;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpMethod;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

/**
 * Shared bounded control-plane Provider transport (M18 round-3).
 *
 * <p>Connection probe, live {@code /models} discovery, completion probe and streaming probe all
 * execute through this boundary with one governed policy: DIRECT (proxy disabled), single
 * DNS resolution with public-only filtering bound to the actual TCP connect (no second
 * unconstrained resolve), default TLS hostname verification (never trust-all), manual redirect
 * handling by callers (never automatic), and a bounded response body. Proxy comes exclusively
 * from {@code noProxy()}: JVM/OS proxy properties are never inherited. Production uses system
 * DNS plus the strict public-unicast policy; alternate DNS/policy combinations are test-only
 * constructor arguments and cannot be enabled through runtime configuration.
 */
@Component
public class ProviderControlPlaneTransport {

    public static final int MAX_BODY_BYTES = ProviderTransportSupport.MAX_BODY_BYTES;

    /** Single response: status, case-insensitive headers, bounded body bytes. */
    public record Result(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public String firstHeader(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? "" : values.get(0);
        }

        public String bodyAsUtf8() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Transport failure with a stable machine-readable code (no secret content). */
    public static final class TransportException extends RuntimeException {
        public static final String DNS_FAILED = "DNS_FAILED";
        public static final String TLS_FAILED = "TLS_FAILED";
        public static final String CONNECTION_TIMEOUT = "CONNECTION_TIMEOUT";
        public static final String CONNECT_FAILED = "CONNECT_FAILED";
        public static final String RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";
        public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";

        private final String errorCode;

        public TransportException(String errorCode, String detail) {
            super(detail);
            this.errorCode = errorCode;
        }

        public TransportException(String errorCode, String detail, Throwable cause) {
            super(detail, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    private static final class BodyTooLargeException extends RuntimeException {
    }

    private final CustomEndpointValidator.DnsResolver dnsResolver;
    private final Predicate<InetAddress> addressPolicy;
    private final ConcurrentHashMap<Integer, HttpClient> clients = new ConcurrentHashMap<>();

    public ProviderControlPlaneTransport() {
        this(CustomEndpointValidator.DnsResolver.system(), CustomEndpointValidator::isPublicUnicast);
    }

    ProviderControlPlaneTransport(CustomEndpointValidator.DnsResolver dnsResolver,
            Predicate<InetAddress> addressPolicy) {
        this.dnsResolver = dnsResolver;
        this.addressPolicy = addressPolicy;
    }

    /** Bounded GET with no request body; redirects are never followed automatically. */
    public Result get(String url, Map<String, String> headers, int connectTimeoutMs,
            int responseTimeoutMs) {
        return exchange("GET", url, headers, null, connectTimeoutMs, responseTimeoutMs);
    }

    /** Bounded POST with a JSON body; redirects are never followed automatically. */
    public Result post(String url, Map<String, String> headers, String body, int connectTimeoutMs,
            int responseTimeoutMs) {
        return exchange("POST", url, headers, body, connectTimeoutMs, responseTimeoutMs);
    }

    private Result exchange(String method, String url, Map<String, String> headers, String body,
            int connectTimeoutMs, int responseTimeoutMs) {
        var timeout = Duration.ofMillis(Math.max(responseTimeoutMs, 1));
        try {
            var sender = clientFor(connectTimeoutMs).request(HttpMethod.valueOf(method)).uri(url);
            var receiver = sender.send((req, out) -> {
                req.responseTimeout(timeout);
                if (headers != null) {
                    headers.forEach(req::header);
                }
                if (body != null) {
                    req.header("Content-Type", "application/json");
                    return out.sendString(Mono.just(body));
                }
                return out.sendString(Mono.<String>empty());
            });
            return receiver.response((resp, flux) -> readBounded(flux, MAX_BODY_BYTES)
                            .map(buffered -> buildResult(resp, buffered)))
                    .single()
                    .block(timeout.plusSeconds(5));
        } catch (TransportException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw mapFailure(ex);
        }
    }

    private HttpClient clientFor(int connectTimeoutMs) {
        int key = Math.max(connectTimeoutMs, 1);
        return clients.computeIfAbsent(key, timeoutMs -> HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .followRedirect(false)
                .resolver(new FilteringAddressResolverGroup(dnsResolver, addressPolicy))
                .noProxy());
    }

    private static Result buildResult(HttpClientResponse response, byte[] body) {
        var collected = new LinkedHashMap<String, List<String>>();
        response.responseHeaders().forEach(entry -> collected
                .computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                .add(entry.getValue()));
        return new Result(response.status().code(), Map.copyOf(collected), body);
    }

    private static Mono<byte[]> readBounded(ByteBufFlux content, int maxBytes) {
        var out = new ByteArrayOutputStream();
        return content.doOnNext(buf -> consumeBounded(buf, out, maxBytes))
                .then(Mono.fromCallable(out::toByteArray));
    }

    private static void consumeBounded(ByteBuf buf, ByteArrayOutputStream out, int maxBytes) {
        // Ownership stays with the transport runtime, which releases each buffer after delivery;
        // only read here, never release (a manual release double-frees).
        try {
            int readable = buf.readableBytes();
            if ((long) out.size() + readable > maxBytes) {
                throw new BodyTooLargeException();
            }
            buf.readBytes(out, readable);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Provider response could not be buffered", ex);
        }
    }

    private static TransportException mapFailure(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof BodyTooLargeException) {
                return new TransportException(TransportException.RESPONSE_TOO_LARGE,
                        "Provider response exceeded the bounded size.", failure);
            }
            if (current instanceof TimeoutException) {
                return new TransportException(TransportException.CONNECTION_TIMEOUT,
                        "Provider request timed out.", failure);
            }
            if (current instanceof UnknownHostException) {
                return new TransportException(TransportException.DNS_FAILED,
                        "Provider DNS resolution failed.", failure);
            }
            if (current instanceof ConnectException) {
                return new TransportException(TransportException.CONNECT_FAILED,
                        "Provider connection was refused.", failure);
            }
            if (current instanceof SSLException) {
                return new TransportException(TransportException.TLS_FAILED,
                        "Provider TLS handshake failed.", failure);
            }
            if (current instanceof SocketTimeoutException) {
                return new TransportException(TransportException.CONNECTION_TIMEOUT,
                        "Provider request timed out.", failure);
            }
            if (current instanceof IllegalStateException illegal
                    && illegal.getMessage() != null
                    && illegal.getMessage().toLowerCase(Locale.ROOT).contains("blocking read")) {
                return new TransportException(TransportException.CONNECTION_TIMEOUT,
                        "Provider request timed out.", failure);
            }
        }
        return new TransportException(TransportException.PROVIDER_UNAVAILABLE,
                "Provider is unavailable.", failure);
    }
}
