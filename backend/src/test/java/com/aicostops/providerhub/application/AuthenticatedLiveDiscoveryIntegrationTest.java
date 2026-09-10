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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Live discovery matrix over a controlled loopback catalog (M18 round-3): configured
 * Bearer/API-key/NONE authentication is really applied with the server-owned UA, READ-only users
 * cannot trigger outbound I/O, failures never masquerade as empty catalogs, and successful live
 * snapshots are the rows in the database (absent ex-AVAILABLE rows become UNAVAILABLE).
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class AuthenticatedLiveDiscoveryIntegrationTest extends ControlPlaneFixtureSupport {

    private static final String BEARER_SECRET = "discovery-bearer-secret";
    private static final String API_KEY_SECRET = "discovery-api-key-secret";

    @Autowired
    private MockMvc mockMvc;

    private HttpServer catalog;
    private int catalogPort;
    private final AtomicInteger catalogHits = new AtomicInteger();
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private final List<String> seenApiKey = new CopyOnWriteArrayList<>();
    private final List<String> seenUserAgent = new CopyOnWriteArrayList<>();
    private final AtomicReference<List<String>> liveModels =
            new AtomicReference<>(List.of("model-a", "model-b"));
    private final AtomicBoolean failNext = new AtomicBoolean(false);

    private long organizationId;
    private long managerUserId;
    private long managerMemberId;
    private long readerUserId;
    private long readerMemberId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        flushRedis();
        cleanDatabase();
        reseedCustomCatalog();
        organizationId = insertOrganization("Discovery Org", "discovery-org");
        managerUserId = insertUser("discovery-manager@example.com");
        managerMemberId = insertMember(organizationId, managerUserId);
        readerUserId = insertUser("discovery-reader@example.com");
        readerMemberId = insertMember(organizationId, readerUserId);
        accountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Discovery Account");
        // Real manager roles bundle READ with MANAGE (refresh answers with a listing, which
        // requires READ; MANAGE alone is correctly rejected there).
        createPermissionRole("DISC_MANAGER",
                List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        createPermissionRole("DISC_READER", List.of("PROVIDER_ACCOUNT_READ"));
        assign(managerMemberId, "DISC_MANAGER", "ORG", organizationId);
        assign(readerMemberId, "DISC_READER", "ORG", organizationId);
        flushRedis();
        catalog = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        catalog.createContext("/models", exchange -> {
            catalogHits.incrementAndGet();
            var auth = exchange.getRequestHeaders().getFirst("Authorization");
            var apiKey = exchange.getRequestHeaders().getFirst("X-Test-Key");
            var agent = exchange.getRequestHeaders().getFirst("User-Agent");
            if (auth != null) seenAuthorization.add(auth);
            if (apiKey != null) seenApiKey.add(apiKey);
            if (agent != null) seenUserAgent.add(agent);
            byte[] payload;
            int code;
            if (failNext.getAndSet(false)) {
                payload = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
                code = 500;
            } else if (("Bearer " + BEARER_SECRET).equals(auth) || API_KEY_SECRET.equals(apiKey)
                    || (auth == null && apiKey == null && openMode())) {
                var body = new StringBuilder("{\"data\":[");
                var first = true;
                for (var id : liveModels.get()) {
                    if (!first) body.append(',');
                    body.append("{\"id\":\"").append(id).append("\"}");
                    first = false;
                }
                payload = body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
                code = 200;
            } else {
                payload = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                code = 401;
            }
            exchange.sendResponseHeaders(code, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        catalog.start();
        catalogPort = catalog.getAddress().getPort();
    }

    private final AtomicBoolean openCatalog = new AtomicBoolean(false);

    private boolean openMode() {
        return openCatalog.get();
    }

    @AfterEach
    void tearDown() {
        if (catalog != null) catalog.stop(0);
        cleanDatabase();
    }

    @Test
    void bearerLiveDiscoveryPersistsSnapshotRows() throws Exception {
        var connectionId = createConnection("{\"providerAccountId\":" + accountId + ",\"baseUrl\":\""
                + baseUrl() + "\",\"authType\":\"BEARER\",\"modelsPath\":\"/models\"}");
        storeCredential(connectionId, BEARER_SECRET);
        refreshLive(connectionId);
        assertEquals(1, catalogHits.get());
        assertTrue(seenAuthorization.contains("Bearer " + BEARER_SECRET));
        assertTrue(seenUserAgent.stream().anyMatch(agent -> !agent.isBlank()));
        assertAvailability(connectionId, "model-a", "AVAILABLE");
        assertAvailability(connectionId, "model-b", "AVAILABLE");
        assertSource(connectionId, "model-a", "LIVE_DISCOVERY");
        // Second successful snapshot without model-a retires exactly that row.
        liveModels.set(List.of("model-b"));
        refreshLive(connectionId);
        assertAvailability(connectionId, "model-a", "UNAVAILABLE");
        assertAvailability(connectionId, "model-b", "AVAILABLE");
    }

    @Test
    void apiKeyAndNoneDiscoveryAuthenticate() throws Exception {
        var apiKeyConnection = createConnection("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"" + baseUrl() + "\",\"authType\":\"API_KEY_HEADER\","
                + "\"authHeaderName\":\"X-Test-Key\",\"modelsPath\":\"/models\"}");
        storeCredential(apiKeyConnection, API_KEY_SECRET);
        refreshLive(apiKeyConnection);
        assertTrue(seenApiKey.contains(API_KEY_SECRET));
        assertAvailability(apiKeyConnection, "model-a", "AVAILABLE");
        openCatalog.set(true);
        var noneConnection = createConnection("{\"providerAccountId\":" + accountId
                + ",\"baseUrl\":\"" + baseUrl() + "\",\"authType\":\"NONE\","
                + "\"modelsPath\":\"/models\"}");
        refreshLive(noneConnection);
        assertAvailability(noneConnection, "model-b", "AVAILABLE");
    }

    @Test
    void readerCannotTriggerOutboundDiscoveryIo() throws Exception {
        var connectionId = createConnection("{\"providerAccountId\":" + accountId + ",\"baseUrl\":\""
                + baseUrl() + "\",\"authType\":\"BEARER\",\"modelsPath\":\"/models\"}");
        storeCredential(connectionId, BEARER_SECRET);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/refresh", connectionId)
                        .header("Authorization", bearerFor(readerUserId)).contentType("application/json")
                        .content("{\"modelNames\":[],\"fetchLive\":true}"))
                .andExpect(status().isForbidden());
        assertEquals(0, catalogHits.get());
    }

    @Test
    void failedSnapshotNeverMarksRowsUnavailable() throws Exception {
        var connectionId = createConnection("{\"providerAccountId\":" + accountId + ",\"baseUrl\":\""
                + baseUrl() + "\",\"authType\":\"BEARER\",\"modelsPath\":\"/models\"}");
        storeCredential(connectionId, BEARER_SECRET);
        refreshLive(connectionId);
        assertAvailability(connectionId, "model-a", "AVAILABLE");
        failNext.set(true);
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/refresh", connectionId)
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"modelNames\":[],\"fetchLive\":true}"))
                .andExpect(status().is5xxServerError());
        assertAvailability(connectionId, "model-a", "AVAILABLE");
        assertAvailability(connectionId, "model-b", "AVAILABLE");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + catalogPort;
    }

    private void refreshLive(long connectionId) throws Exception {
        var result = mockMvc.perform(post("/api/v1/provider-connections/{id}/models/refresh", connectionId)
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"modelNames\":[],\"fetchLive\":true}"))
                .andReturn();
        var body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "refresh body: " + body);
    }

    private long createConnection(String json) throws Exception {
        var body = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void storeCredential(long connectionId, String secret) throws Exception {
        mockMvc.perform(post("/api/v1/provider-connections/{id}/credentials", connectionId)
                        .header("Authorization", bearerFor(managerUserId)).contentType("application/json")
                        .content("{\"rawSecret\":\"" + secret + "\",\"safeLabel\":\"t\"}"))
                .andExpect(status().isCreated());
    }

    private void assertAvailability(long connectionId, String model, String expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT availability FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?",
                String.class, organizationId, connectionId, model));
    }

    private void assertSource(long connectionId, String model, String expected) {
        assertEquals(expected, jdbc.queryForObject("SELECT source FROM provider_model_discovery"
                + " WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name=?",
                String.class, organizationId, connectionId, model));
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
                + " WHERE r.code IN ('DISC_MANAGER','DISC_READER')");
        jdbc.update("DELETE FROM `role` WHERE code IN ('DISC_MANAGER','DISC_READER')");
    }
}
