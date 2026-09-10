package com.aicostops.providerhub.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.OutputStream;
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
 * P1-2 fractional SSE index negative: a chunk with index 0.5 plus terminal DONE must never verify.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@AutoConfigureMockMvc
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class SseFractionalIndexIntegrationTest extends ControlPlaneFixtureSupport {

    private static final String COMPLETION_JSON = "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\","
            + "\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
            + "\"completion_tokens\":1,\"total_tokens\":2}}";

    @Autowired
    private MockMvc mockMvc;

    private com.sun.net.httpserver.HttpServer fractionalProvider;
    private int fractionalPort;
    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        flushRedis();
        cleanDatabase();
        reseedCustomCatalog();
        organizationId = insertOrganization("Fractional Org", "fractional-org");
        actorUserId = insertUser("fractional-admin@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        accountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Fractional Account");
        createPermissionRole("FRACTIONAL_MANAGER",
                List.of("PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(actorMemberId, "FRACTIONAL_MANAGER", "ORG", organizationId);
        flushRedis();
        fractionalProvider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fractionalProvider.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                var bad = "data: {\"choices\":[{\"index\":0.5,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}\n\n" + "data: [DONE]\n\n";
                payload = bad.getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                payload = COMPLETION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        fractionalProvider.start();
        fractionalPort = fractionalProvider.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (fractionalProvider != null) fractionalProvider.stop(0);
        cleanDatabase();
    }

    @Test
    void fractionalIndexChunkWithDoneNeverVerifiesStreaming() throws Exception {
        var connectionId = createConnection(fractionalPort);
        var discoveryId = manualModel(connectionId, "stream-model");
        mockMvc.perform(post("/api/v1/provider-connections/{id}/models/{discoveryId}/probe", connectionId, discoveryId)
                        .header("Authorization", bearerFor(actorUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.capabilities.CHAT_COMPLETIONS").value("VERIFIED"))
                .andExpect(jsonPath("$.capabilities.SSE_STREAMING").value("UNSUPPORTED"));
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
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code='FRACTIONAL_MANAGER'");
        jdbc.update("DELETE FROM `role` WHERE code='FRACTIONAL_MANAGER'");
    }
}
