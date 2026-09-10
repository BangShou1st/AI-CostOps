package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Control-plane DNS-rebinding evidence (M18 round-3, no Spring needed).
 *
 * <p>Proves resolution, policy filtering and TCP connect happen in one transport boundary:
 * a flipping DNS source cannot smuggle a second destination into the actual connection, and a
 * strict policy fails before any byte is sent. Proxy properties are poisoned to prove DIRECT.
 */
class ProviderControlPlaneDnsRebindingIntegrationTest {

    private HttpServer server;
    private HttpServer poisonProxy;
    private final AtomicInteger serverHits = new AtomicInteger();
    private final AtomicInteger poisonHits = new AtomicInteger();
    private final AtomicInteger dnsCalls = new AtomicInteger();
    private int serverPort;

    @BeforeEach
    void startServers() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            serverHits.incrementAndGet();
            var payload = "{\"data\":[{\"id\":\"model-a\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        serverPort = server.getAddress().getPort();
        poisonProxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        poisonProxy.createContext("/", exchange -> {
            poisonHits.incrementAndGet();
            var payload = "blocked".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        poisonProxy.start();
    }

    @AfterEach
    void stopServers() {
        if (server != null) server.stop(0);
        if (poisonProxy != null) poisonProxy.stop(0);
    }

    @Test
    void singleResolutionIsBoundToTheActualConnection() {
        // True rebinding simulation: the first resolution observes the controlled server while any
        // second resolution would observe 127.0.0.2 (unbound: using it fails fast). Exactly one
        // DNS call plus a good response proves the first resolution alone fed the actual connect.
        var transport = new ProviderControlPlaneTransport(host -> {
            var call = dnsCalls.incrementAndGet();
            var address = call == 1 ? "127.0.0.1" : "127.0.0.2";
            return new InetAddress[] { InetAddress.getByName(address) };
        }, addr -> true);
        var saved = poisonJvmProxy();
        try {
            var result = transport.get("http://pin-test.local:" + serverPort + "/models",
                    Map.of("Accept", "application/json"), 5000, 10000);
            assertEquals(200, result.statusCode());
            assertTrue(result.bodyAsUtf8().contains("model-a"));
            assertEquals(1, serverHits.get());
            assertEquals(1, dnsCalls.get());
            assertEquals(0, poisonHits.get());
        } finally {
            restoreJvmProxy(saved);
        }
    }

    @Test
    void strictPolicyFailsBeforeAnyByteIsSent() {
        var strict = new ProviderControlPlaneTransport(
                host -> new InetAddress[] { InetAddress.getByName("10.9.9.9") },
                CustomEndpointValidator::isPublicUnicast);
        var before = serverHits.get();
        var failure = assertThrows(ProviderControlPlaneTransport.TransportException.class,
                () -> strict.get("http://rebind-target.example:" + serverPort + "/models",
                        Map.of("Accept", "application/json"), 5000, 10000));
        assertEquals(ProviderControlPlaneTransport.TransportException.DNS_FAILED, failure.errorCode());
        assertEquals(before, serverHits.get());
    }

    @Test
    void redirectsAreNeverFollowedAutomatically() throws Exception {
        var redirecting = new ProviderControlPlaneTransport(
                host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") }, addr -> true);
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            target.createContext("/jump", exchange -> {
                exchange.getResponseHeaders().add("Location", "/models");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            target.start();
            var result = redirecting.get(
                    "http://127.0.0.1:" + target.getAddress().getPort() + "/jump",
                    Map.of("Accept", "application/json"), 5000, 10000);
            assertEquals(302, result.statusCode());
            assertEquals("/models", result.firstHeader("location"));
        } catch (Exception ex) {
            fail(ex);
        } finally {
            target.stop(0);
        }
    }

    @Test
    void oversizedBodiesAreRejectedBounded() throws Exception {
        var big = new ProviderControlPlaneTransport(
                host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") }, addr -> true);
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            target.createContext("/big", exchange -> {
                var payload = new byte[100 * 1024];
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            target.start();
            var failure = assertThrows(ProviderControlPlaneTransport.TransportException.class,
                    () -> big.get("http://127.0.0.1:" + target.getAddress().getPort() + "/big",
                            Map.of("Accept", "application/json"), 5000, 10000));
            assertEquals(ProviderControlPlaneTransport.TransportException.RESPONSE_TOO_LARGE,
                    failure.errorCode());
        } catch (Exception ex) {
            fail(ex);
        } finally {
            target.stop(0);
        }
    }

    private String[] poisonJvmProxy() {
        var saved = new String[] { System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"),
                System.getProperty("https.proxyHost"), System.getProperty("https.proxyPort") };
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(poisonProxy.getAddress().getPort()));
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(poisonProxy.getAddress().getPort()));
        return saved;
    }

    private void restoreJvmProxy(String[] saved) {
        restore("http.proxyHost", saved[0]);
        restore("http.proxyPort", saved[1]);
        restore("https.proxyHost", saved[2]);
        restore("https.proxyPort", saved[3]);
    }

    private void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
