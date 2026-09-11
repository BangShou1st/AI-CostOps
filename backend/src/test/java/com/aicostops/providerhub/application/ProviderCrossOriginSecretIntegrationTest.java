package com.aicostops.providerhub.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Redirect secret containment over controlled loopback Providers (M18 round-3): server A
 * receives the credential and redirects to a different origin (server B, different port); the
 * probe must reject without emitting the next request, so server B observes zero secrets.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class ProviderCrossOriginSecretIntegrationTest extends ControlPlaneFixtureSupport {

    private static final String BEARER_SECRET = "redirect-bearer-secret";
    private static final String API_KEY_SECRET = "redirect-api-key-secret";
    private static final String COMPLETION_JSON = "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\","
            + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}";

    @Autowired
    private MockMvc mockMvc;

    private HttpServer serverA;
    private HttpServer serverB;
    private int portA;
    private int portB;
    private final AtomicInteger hitsA = new AtomicInteger();
    private final AtomicInteger hitsB = new AtomicInteger();
    private final List<String> authAtA = new CopyOnWriteArrayList<>();
    private final List<String> authAtB = new CopyOnWriteArrayList<>();
    private final List<String> apiKeyAtB = new CopyOnWriteArrayList<>();

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        flushRedis();
        cleanDatabase();
        reseedCustomCatalog();
        organizationId = insertOrganization("Secret Org", "secret-org");
        actorUserId = insertUser("secret-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        accountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Secret Account");
        createPermissionRole("SECRET_MANAGER",
                List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(actorMemberId, "SECRET_MANAGER", "ORG", organizationId);
        flushRedis();
        serverB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverB.createContext("/completions", exchange -> {
            hitsB.incrementAndGet();
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            var apiKey = exchange.getRequestHeaders().getFirst("X-Secret-Key");
            if (auth != null) authAtB.add(auth);
            if (apiKey != null) apiKeyAtB.add(apiKey);
            var payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        serverB.start();
        portB = serverB.getAddress().getPort();
        serverA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverA.createContext("/chat/completions", exchange -> {
            hitsA.incrementAndGet();
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) authAtA.add(auth);
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + portB + "/completions");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        serverA.start();
        portA = serverA.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (serverA != null) serverA.stop(0);
        if (serverB != null) serverB.stop(0);
        cleanDatabase();
    }

    @Test
    void bearerSecretNeverReachesCrossOriginRedirect() throws Exception {
        var connectionId = createConnection("{\"providerAccountId\":" + accountId + ",\"baseUrl\":\""
                + baseUrlA() + "\",\"authType\":\"BEARER\"}");
        storeCredential(connectionId, BEARER_SECRET);
        var discoveryId = manualModel(connectionId, "redirect-model");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe",
                        connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.errorCode").value("ENDPOINT_BLOCKED"));
        assertEquals(1, hitsA.get());
        assertTrue(authAtA.contains("Bearer " + BEARER_SECRET));
        assertEquals(0, hitsB.get());
        assertTrue(authAtB.isEmpty());
    }

    @Test
    void apiKeySecretNeverReachesCrossOriginRedirect() throws Exception {
        var connectionId = createConnection("{\"providerAccountId\":" + accountId + ",\"baseUrl\":\""
                + baseUrlA() + "\",\"authType\":\"API_KEY_HEADER\","
                + "\"authHeaderName\":\"X-Secret-Key\"}");
        storeCredential(connectionId, API_KEY_SECRET);
        var discoveryId = manualModel(connectionId, "redirect-model");
        // Server A redirects unconditionally; the API-key request must be rejected at the
        // cross-origin hop exactly like Bearer, with zero secret bytes reaching server B.
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe",
                        connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.errorCode").value("ENDPOINT_BLOCKED"));
        assertEquals(0, hitsB.get());
        assertTrue(apiKeyAtB.isEmpty());
        assertTrue(authAtB.isEmpty());
    }

    private String baseUrlA() {
        return "http://127.0.0.1:" + portA;
    }

    private long createConnection(String json) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", bearerFor(actorUserId)).contentType("application/json")
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void storeCredential(long connectionId, String secret) throws Exception {
        mockMvc.perform(post("/api/v1/provider-connections/{id}/credentials", connectionId)
                        .header("Authorization", bearerFor(actorUserId)).contentType("application/json")
                        .content("{\"rawSecret\":\"" + secret + "\",\"safeLabel\":\"t\"}"))
                .andExpect(status().isCreated());
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
                + " WHERE r.code='SECRET_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='SECRET_MANAGER'");
    }
}
