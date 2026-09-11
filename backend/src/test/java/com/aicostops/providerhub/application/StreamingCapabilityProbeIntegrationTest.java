package com.aicostops.providerhub.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Streaming capability evidence over a controlled loopback Provider (M18 round-3):
 * SSE_STREAMING becomes VERIFIED only through a real bounded {@code stream=true} exchange
 * carrying {@code text/event-stream} plus a legal Chat Completion chunk; a JSON-only endpoint
 * stays UNSUPPORTED and is never inferred from the non-streaming call.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class StreamingCapabilityProbeIntegrationTest extends ControlPlaneFixtureSupport {

    private static final String COMPLETION_JSON = "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\","
            + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
            + "\"completion_tokens\":1,\"total_tokens\":2}}";
    private static final String SSE_BODY =
            "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},"
                    + "\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";

    @Autowired
    private MockMvc mockMvc;

    private HttpServer sseProvider;
    private HttpServer jsonOnlyProvider;
    private HttpServer truncatedProvider;
    private HttpServer fakeChoicesProvider;
    private int ssePort;
    private int jsonOnlyPort;
    private int truncatedPort;
    private int fakeChoicesPort;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        flushRedis();
        cleanDatabase();
        reseedCustomCatalog();
        organizationId = insertOrganization("Stream Org", "stream-org");
        actorUserId = insertUser("stream-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        accountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Stream Account");
        createPermissionRole("STREAM_MANAGER",
                List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(actorMemberId, "STREAM_MANAGER", "ORG", organizationId);
        flushRedis();
        sseProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sseProvider.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                payload = SSE_BODY.getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        sseProvider.start();
        ssePort = sseProvider.getAddress().getPort();
        jsonOnlyProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jsonOnlyProvider.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            var payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        jsonOnlyProvider.start();
        jsonOnlyPort = jsonOnlyProvider.getAddress().getPort();
        truncatedProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        truncatedProvider.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                var chunk = "data: {\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}\n\n";
                payload = chunk.getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        truncatedProvider.start();
        truncatedPort = truncatedProvider.getAddress().getPort();
        fakeChoicesProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeChoicesProvider.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            var payload = "{\"foo\":\"choices\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        fakeChoicesProvider.start();
        fakeChoicesPort = fakeChoicesProvider.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (sseProvider != null) sseProvider.stop(0);
        if (jsonOnlyProvider != null) jsonOnlyProvider.stop(0);
        if (truncatedProvider != null) truncatedProvider.stop(0);
        if (fakeChoicesProvider != null) fakeChoicesProvider.stop(0);
        cleanDatabase();
    }

    @Test
    void realStreamingExchangeVerifiesSseCapability() throws Exception {
        var connectionId = createConnection(ssePort);
        var discoveryId = manualModel(connectionId, "stream-model");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe",
                        connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.capabilities.CHAT_COMPLETIONS").value("VERIFIED"))
                .andExpect(jsonPath("$.capabilities.SSE_STREAMING").value("VERIFIED"));
    }

    @Test
    void jsonOnlyEndpointLeavesStreamingUnsupported() throws Exception {
        var connectionId = createConnection(jsonOnlyPort);
        var discoveryId = manualModel(connectionId, "stream-model");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe",
                        connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.capabilities.CHAT_COMPLETIONS").value("VERIFIED"))
                .andExpect(jsonPath("$.capabilities.SSE_STREAMING").value("UNSUPPORTED"));
    }

    @Test
    void truncatedStreamWithoutTerminalIsNeverVerified() throws Exception {
        var connectionId = createConnection(truncatedPort);
        var discoveryId = manualModel(connectionId, "stream-model");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe",
                        connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.capabilities.CHAT_COMPLETIONS").value("VERIFIED"))
                .andExpect(jsonPath("$.capabilities.SSE_STREAMING").value("UNKNOWN"));
    }

    @Test
    void fakeChoicesSubstringIsNeverVerified() throws Exception {
        var connectionId = createConnection(fakeChoicesPort);
        var discoveryId = manualModel(connectionId, "stream-model");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe",
                        connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.capabilities.CHAT_COMPLETIONS").value("UNSUPPORTED"));
    }
    @Test void structurallyInvalidChunkNeverVerifiesStreaming() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                var bad = "data: {\"choices\":[1]}\n\n" + "data: [DONE]\n\n";
                payload = bad.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = COMPLETION_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) { out.write(payload); }
        });
        server.start();
        try {
            var connectionId = createConnection(server.getAddress().getPort());
            var discoveryId = manualModel(connectionId, "stream-model");
            mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe", connectionId, discoveryId).header("Authorization", bearerFor(actorUserId))).andExpect(status().isOk()).andExpect(jsonPath("$.pass").value(true)).andExpect(jsonPath("$.capabilities.CHAT_COMPLETIONS").value("VERIFIED")).andExpect(jsonPath("$.capabilities.SSE_STREAMING").value("UNSUPPORTED"));
        } finally { server.stop(0); }
    }

    private long createConnection(int port) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", bearerFor(actorUserId)).contentType("application/json")
                        .content("{\"providerAccountId\":" + accountId + ",\"baseUrl\":\"http://127.0.0.1:"
                                + port + "\",\"authType\":\"NONE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long manualModel(long connectionId, String name) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections/{id}/models/manual", connectionId)
                        .header("Authorization", bearerFor(actorUserId)).contentType("application/json")
                        .content("{\"modelName\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM provider_model_discovery");
        jdbc.update("DELETE FROM provider_connection_profile");
        jdbc.update("UPDATE provider_credential SET predecessor_credential_id=NULL");
        jdbc.update("DELETE FROM provider_credential");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code='STREAM_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='STREAM_MANAGER'");
    }
}
