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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Connection-probe authentication matrix over controlled loopback servers (M18 round-3): the
 * configured credential is really applied, and a missing/unusable credential fails closed with
 * zero outbound requests instead of downgrading to an anonymous request that could false-PASS.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class AuthenticatedConnectionProbeIntegrationTest extends ControlPlaneFixtureSupport {

    private static final String BEARER_SECRET = "probe-bearer-secret";
    private static final String API_KEY_SECRET = "probe-api-key-secret";

    @Autowired
    private MockMvc mockMvc;

    private HttpServer provider;
    private int providerPort;
    private final AtomicInteger modelsHits = new AtomicInteger();
    private final AtomicInteger openHits = new AtomicInteger();
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private final List<String> seenApiKey = new CopyOnWriteArrayList<>();

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        flushRedis();
        cleanDatabase();
        reseedCustomCatalog();
        organizationId = insertOrganization("Probe Org", "probe-org");
        actorUserId = insertUser("probe-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        accountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Probe Account");
        createPermissionRole("PROBE_MANAGER", List.of("PROVIDER_ACCOUNT_MANAGE"));
        assign(actorMemberId, "PROBE_MANAGER", "ORG", organizationId);
        flushRedis();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/models", exchange -> {
            modelsHits.incrementAndGet();
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            var apiKey = exchange.getRequestHeaders().getFirst("X-Test-Key");
            if (auth != null) seenAuthorization.add(auth);
            if (apiKey != null) seenApiKey.add(apiKey);
            byte[] payload;
            if (("Bearer " + BEARER_SECRET).equals(auth) || API_KEY_SECRET.equals(apiKey)) {
                payload = "{\"data\":[{\"id\":\"m1\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
            } else {
                payload = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, payload.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        provider.createContext("/open", exchange -> {
            openHits.incrementAndGet();
            var payload = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        provider.start();
        providerPort = provider.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (provider != null) provider.stop(0);
        cleanDatabase();
    }

    @Test
    void bearerProbeAppliesConfiguredCredential() throws Exception {
        var connectionId = createConnection(
                "{\"providerAccountId\":" + accountId + ",\"baseUrl\":\"" + baseUrl()
                        + "\",\"authType\":\"BEARER\",\"modelsPath\":\"/models\"}");
        storeCredential(connectionId, BEARER_SECRET);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/probe", connectionId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"));
        assertEquals(1, modelsHits.get());
        assertTrue(seenAuthorization.contains("Bearer " + BEARER_SECRET));
    }

    @Test
    void apiKeyProbeAppliesConfiguredCredential() throws Exception {
        var connectionId = createConnection(
                "{\"providerAccountId\":" + accountId + ",\"baseUrl\":\"" + baseUrl()
                        + "\",\"authType\":\"API_KEY_HEADER\",\"authHeaderName\":\"X-Test-Key\","
                        + "\"modelsPath\":\"/models\"}");
        storeCredential(connectionId, API_KEY_SECRET);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/probe", connectionId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"));
        assertEquals(1, modelsHits.get());
        assertTrue(seenApiKey.contains(API_KEY_SECRET));
    }

    @Test
    void missingCredentialFailsClosedWithZeroOutboundRequests() throws Exception {
        var connectionId = createConnection(
                "{\"providerAccountId\":" + accountId + ",\"baseUrl\":\"" + baseUrl()
                        + "\",\"authType\":\"BEARER\",\"modelsPath\":\"/models\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/probe", connectionId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        assertEquals(0, modelsHits.get());
    }

    @Test
    void anonymousOpenEndpointDoesNotFalsePassWithoutCredential() throws Exception {
        var connectionId = createConnection(
                "{\"providerAccountId\":" + accountId + ",\"baseUrl\":\"" + baseUrl()
                        + "\",\"authType\":\"BEARER\",\"modelsPath\":\"/open\"}");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/probe", connectionId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        assertEquals(0, openHits.get());
        assertEquals(0, modelsHits.get());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + providerPort;
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
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code='PROBE_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='PROBE_MANAGER'");
    }
}
