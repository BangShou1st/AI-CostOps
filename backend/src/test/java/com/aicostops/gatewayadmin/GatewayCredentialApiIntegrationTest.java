package com.aicostops.gatewayadmin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.gatewayadmin.security.GatewayKeyCodec;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Governed Gateway Credential surface: one-time raw secret on create, raw key
 * and digest never recoverable afterwards, deterministic revoke (repeat revoke
 * conflicts), cross-org isolation, audit — and the digest stored at rest
 * matches HMAC-SHA-256 of the secret part only.
 */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class GatewayCredentialApiIntegrationTest extends ControlPlaneApiTestSupport {

    private static final String HMAC_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createReturnsRawKeyExactlyOnceAndStoresOnlyPrefixPlusDigest() throws Exception {
        long serviceIdentityId = insertServiceIdentity(orgId, "cred-bot-" + fixtureCounter);
        var create = mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialJson(serviceIdentityId, projectId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.rawKey").isString())
                .andExpect(jsonPath("$.prefix").isString())
                .andReturn();

        var json = readJson(create);
        String rawKey = json.get("rawKey").asText();
        String prefix = json.get("prefix").asText();
        long id = Long.parseLong(json.get("id").asText());

        // Contract shape + digest stored in the table, the only two credential
        // secrets we ever persist.
        var parsed = GatewayKeyCodec.parse(rawKey);
        org.assertj.core.api.Assertions.assertThat(rawKey).startsWith("aic_").hasSize(60);
        var row = jdbc.queryForMap("SELECT credential_prefix, secret_digest FROM gateway_credential WHERE id=?", id);
        org.assertj.core.api.Assertions.assertThat(row.get("credential_prefix")).isEqualTo(parsed.prefix());
        byte[] storedDigest = (byte[]) row.get("secret_digest");
        byte[] expectedDigest = hmac(parsed.secretPart());
        org.assertj.core.api.Assertions.assertThat(storedDigest).isEqualTo(expectedDigest);
        // The raw key is not persisted anywhere in the credential row.
        var digestText = new String(storedDigest, StandardCharsets.ISO_8859_1);
        org.assertj.core.api.Assertions.assertThat(digestText).doesNotContain(rawKey);

        // List and detail never recover the raw key or the digest.
        mockMvc.perform(get("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prefix").value(prefix))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/gateway-credentials/" + id)
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawKey").doesNotExist())
                .andExpect(jsonPath("$.secretDigest").doesNotExist())
                .andExpect(jsonPath("$.prefix").value(prefix));
    }

    @Test
    void createRequiresManagePermissionAndServiceIdentityInScope() throws Exception {
        long serviceIdentityId = insertServiceIdentity(orgId, "cred-bot-" + fixtureCounter);
        mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", plainBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialJson(serviceIdentityId, projectId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        long foreignIdentityId = insertServiceIdentity(foreignOrgId, "foreign-bot-" + fixtureCounter);
        mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialJson(foreignIdentityId, projectId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void foreignCredentialIsInvisible() throws Exception {
        long foreignIdentityId = insertServiceIdentity(foreignOrgId, "foreign-bot-" + fixtureCounter);
        var create = mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", foreignManagerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialJson(foreignIdentityId, foreignProjectId)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readJson(create).get("id").asLong();

        mockMvc.perform(get("/api/v1/gateway-credentials/" + id)
                        .header("Authorization", managerBearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/gateway-credentials/" + id + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokeTransitionsToRevokedAndRepeatRevokeConflictsDeterministically() throws Exception {
        long serviceIdentityId = insertServiceIdentity(orgId, "revoke-bot-" + fixtureCounter);
        var create = mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialJson(serviceIdentityId, projectId)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readJson(create).get("id").asLong();

        mockMvc.perform(post("/api/v1/gateway-credentials/" + id + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM gateway_credential WHERE id=?", java.sql.Timestamp.class, id))
                .isNotNull();
        // Repeat revoke is a deterministic conflict, not a silent success.
        mockMvc.perform(post("/api/v1/gateway-credentials/" + id + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void credentialLifecycleIsAudited() throws Exception {
        long serviceIdentityId = insertServiceIdentity(orgId, "audit-bot-" + fixtureCounter);
        var create = mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentialJson(serviceIdentityId, projectId)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = readJson(create).get("id").asLong();
        mockMvc.perform(post("/api/v1/gateway-credentials/" + id + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit-events")
                        .param("orgId", Long.toString(orgId))
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("GATEWAY_CREDENTIAL_REVOKED"))
                .andExpect(jsonPath("$.items[1].eventType").value("GATEWAY_CREDENTIAL_CREATED"));
    }

    @Test
    void humanMemberCredentialRequiresAnActiveOrganizationMember() throws Exception {
        mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "principalType":"HUMAN_MEMBER",
                                  "organizationMemberId":%d,
                                  "projectId":%d,
                                  "financialScopeType":"PROJECT",
                                  "financialScopeId":%d,
                                  "budgetEnforcementMode":"OPTIONAL",
                                  "modelIds":[%d]
                                }
                                """.formatted(managerMemberId, projectId, projectId, modelId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principalType").value("HUMAN_MEMBER"));

        mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "principalType":"HUMAN_MEMBER",
                                  "organizationMemberId":999999,
                                  "projectId":%d,
                                  "financialScopeType":"PROJECT",
                                  "financialScopeId":%d,
                                  "budgetEnforcementMode":"REQUIRED",
                                  "modelIds":[%d]
                                }
                                """.formatted(projectId, projectId, modelId)))
                .andExpect(status().isNotFound());
    }

    private String credentialJson(long serviceIdentityId, long project) {
        return """
                {
                  "principalType":"SERVICE",
                  "serviceIdentityId":%d,
                  "projectId":%d,
                  "financialScopeType":"PROJECT",
                  "financialScopeId":%d,
                  "budgetEnforcementMode":"OPTIONAL",
                  "modelIds":[%d]
                }
                """.formatted(serviceIdentityId, project, project, modelId);
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                result.getResponse().getContentAsByteArray());
    }

    private static byte[] hmac(String secretPart) throws Exception {
        var key = GatewayKeyCodec.decode32ByteKey("AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1", HMAC_KEY);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(secretPart.getBytes(StandardCharsets.UTF_8));
    }
}