package com.aicostops.gatewayadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.shared.cache.GatewayConfigInvalidationPublisher;
import com.aicostops.shared.cache.GatewayConfigInvalidationPublisher.InvalidationMessage;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies Control-Plane AFTER_COMMIT Redis L2 DEL + Pub/Sub invalidation
 * across all config-mutating services, plus rollback suppression and fail-open
 * behavior when Redis is unavailable.
 */
@SpringBootTest(properties = {
        "aicostops.gateway.provider-kek-v1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@Tag("integration")
@AutoConfigureMockMvc
class GatewayConfigInvalidationIntegrationTest extends ControlPlaneApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GatewayConfigInvalidationPublisher invalidations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private StringRedisTemplate spyRedis;

    private RedisMessageListenerContainer listenerContainer;
    private final BlockingQueue<InvalidationMessage> receivedMessages = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUpInvalidationListener() {
        Mockito.reset(spyRedis);
        receivedMessages.clear();
        cleanupCustomInvRole();
        // Grant extra permissions needed by project/team/cost-center/advisor endpoints.
        createPermissionRole("CP_INV_ALL", List.of(
                "PROVIDER_ACCOUNT_READ", "PROVIDER_ACCOUNT_MANAGE", "AUDIT_READ",
                "PROJECT_MANAGE", "TEAM_MANAGE", "COST_CENTER_MANAGE",
                "AI_ADVISOR_USE", "AI_ADVISOR_MANAGE"));
        assign("CP_INV_ALL", "ORG", orgId, managerMemberId);

        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES ('CUSTOM_OPENAI_COMPATIBLE','Custom OpenAI-Compatible','CUSTOM_OPENAI_COMPATIBLE',
                  'https://example.invalid','ACTIVE',JSON_OBJECT('capabilities',JSON_ARRAY('CHAT_COMPLETIONS')),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=UTC_TIMESTAMP(6)
                """);

        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(spyRedis.getConnectionFactory());
        listenerContainer.addMessageListener(
                (message, pattern) -> {
                    var body = new String(message.getBody(), StandardCharsets.UTF_8);
                    var parsed = objectMapper.readValue(body, InvalidationMessage.class);
                    receivedMessages.offer(parsed);
                },
                ChannelTopic.of(GatewayConfigInvalidationPublisher.INVALIDATION_CHANNEL));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        // Allow Pub/Sub listener registration to settle.
        long settle = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        while (System.nanoTime() < settle) {
            Thread.onSpinWait();
        }
    }

    @AfterEach
    void tearDownInvalidationListener() throws Exception {
        Mockito.reset(spyRedis);
        if (listenerContainer != null) {
            listenerContainer.destroy();
        }
        jdbc.update("UPDATE provider_credential SET predecessor_credential_id=NULL");
        cleanupCustomInvRole();
    }

    private void cleanupCustomInvRole() {
        jdbc.update("""
                DELETE ra FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id
                WHERE r.code='CP_INV_ALL'
                """);
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code='CP_INV_ALL'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='CP_INV_ALL'");
    }

    @Test
    void credentialPricingAndRoutingMutationsDeleteRedisKeysAndPublishAfterCommit() throws Exception {
        long svcId = insertServiceIdentity(orgId, "inv-svc-" + fixtureCounter);
        var credRes = mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "principalType":"SERVICE",
                                  "serviceIdentityId":%d,
                                  "projectId":%d,
                                  "financialScopeType":"PROJECT",
                                  "financialScopeId":%d,
                                  "budgetEnforcementMode":"OPTIONAL",
                                  "modelIds":[%d]
                                }
                                """.formatted(svcId, projectId, projectId, modelId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long credId = readLong(credRes, "$.id");
        byte[] digestBytes = jdbc.queryForObject(
                "SELECT secret_digest FROM gateway_credential WHERE id=?", byte[].class, credId);
        String digestHex = HexFormat.of().formatHex(digestBytes);

        assertThat(awaitMessage("CRED", digestHex)).isTrue();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(credId))).isTrue();
        receivedMessages.clear();

        // Plant L2 entries and verify revoke DEL + PUBLISH cleans them after commit.
        String credRedisKey = GatewayConfigInvalidationPublisher.PREFIX_CRED + digestHex;
        String principalRedisKey = GatewayConfigInvalidationPublisher.PREFIX_PRINCIPAL + credId;
        spyRedis.opsForValue().set(credRedisKey, "{\"cached\":true}");
        spyRedis.opsForValue().set(principalRedisKey, "{\"cached\":true}");

        mockMvc.perform(post("/api/v1/gateway-credentials/" + credId + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());

        assertThat(spyRedis.hasKey(credRedisKey)).isFalse();
        assertThat(spyRedis.hasKey(principalRedisKey)).isFalse();
        assertThat(awaitMessage("CRED", digestHex)).isTrue();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(credId))).isTrue();
        receivedMessages.clear();

        // Pricing version create + activate -> DEL + PUBLISH PRICING & ROUTE.
        var pvRes = mockMvc.perform(post("/api/v1/pricing-versions")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerAccountId":%d,
                                  "providerModelId":%d,
                                  "currency":"USD",
                                  "effectiveFrom":"2026-09-08T00:00:00Z",
                                  "rates":[
                                    {"dimensionCode":"INPUT_TOKEN","unitQuantity":1000000,"unitPrice":10.0},
                                    {"dimensionCode":"OUTPUT_TOKEN","unitQuantity":1000000,"unitPrice":20.0}
                                  ]
                                }
                                """.formatted(providerAccountId, providerModelId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long pvId = readLong(pvRes, "$.id");
        assertThat(awaitMessage("PRICING", Long.toString(pvId))).isTrue();
        receivedMessages.clear();

        String pvRedisKey = GatewayConfigInvalidationPublisher.PREFIX_PRICING + pvId;
        String routeRedisKey = GatewayConfigInvalidationPublisher.PREFIX_ROUTE + orgId + ":" + modelId;
        spyRedis.opsForValue().set(pvRedisKey, "{\"cached\":true}");
        spyRedis.opsForValue().set(routeRedisKey, "{\"cached\":true}");

        mockMvc.perform(post("/api/v1/pricing-versions/" + pvId + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());

        assertThat(spyRedis.hasKey(pvRedisKey)).isFalse();
        assertThat(spyRedis.hasKey(routeRedisKey)).isFalse();
        assertThat(awaitMessage("PRICING", Long.toString(pvId))).isTrue();
        assertThat(awaitMessage("ROUTE", orgId + ":" + modelId)).isTrue();
        receivedMessages.clear();

        // Routing policy create / update / activate / revise -> DEL + PUBLISH ROUTE.
        String policyPayload = """
                {"modelId":%d,"candidates":[{"providerAccountId":%d,"providerModelId":%d,
                  "priority":0,"status":"ACTIVE"}]}
                """.formatted(modelId, providerAccountId, providerModelId);
        var rpCreate = mockMvc.perform(post("/api/v1/routing-policies")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyPayload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long rpId = readLong(rpCreate, "$.id");
        assertThat(awaitMessage("ROUTE", orgId + ":" + modelId)).isTrue();
        receivedMessages.clear();

        spyRedis.opsForValue().set(routeRedisKey, "{\"cached\":true}");
        mockMvc.perform(put("/api/v1/routing-policies/" + rpId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidates":[{"providerAccountId":%d,"providerModelId":%d,
                                  "priority":1,"status":"ACTIVE"}]}
                                """.formatted(providerAccountId, providerModelId)))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(routeRedisKey)).isFalse();
        assertThat(awaitMessage("ROUTE", orgId + ":" + modelId)).isTrue();
        receivedMessages.clear();

        spyRedis.opsForValue().set(routeRedisKey, "{\"cached\":true}");
        mockMvc.perform(post("/api/v1/routing-policies/" + rpId + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(routeRedisKey)).isFalse();
        assertThat(awaitMessage("ROUTE", orgId + ":" + modelId)).isTrue();
        receivedMessages.clear();

        mockMvc.perform(post("/api/v1/routing-policies/" + rpId + "/revisions")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isCreated());
        assertThat(awaitMessage("ROUTE", orgId + ":" + modelId)).isTrue();
    }

    @Test
    void providerHubConnectionCredentialDiscoveryAndOrgAccountMutationsInvalidate() throws Exception {
        // Create a CUSTOM_OPENAI_COMPATIBLE account via HTTP -> publishes ROUTE_ORG.
        var acctRes = mockMvc.perform(post("/api/v1/provider-accounts")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerCode":"CUSTOM_OPENAI_COMPATIBLE","displayName":"Custom Inv"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long customAccountId = readLong(acctRes, "$.id");
        assertThat(awaitMessage("ROUTE_ORG", Long.toString(orgId))).isTrue();
        receivedMessages.clear();

        // Update provider account -> DEL cfg:route:{orgId}:* + PUBLISH ROUTE_ORG & PROVIDER_CRED.
        String routeWildcard = GatewayConfigInvalidationPublisher.PREFIX_ROUTE + orgId + ":999";
        spyRedis.opsForValue().set(routeWildcard, "{\"cached\":true}");
        mockMvc.perform(patch("/api/v1/provider-accounts/" + customAccountId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Custom Inv Updated\"}"))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(routeWildcard)).isFalse();
        assertThat(awaitMessage("ROUTE_ORG", Long.toString(orgId))).isTrue();
        assertThat(awaitMessage("PROVIDER_CRED", orgId + ":" + customAccountId)).isTrue();
        receivedMessages.clear();

        // ProviderConnection create -> updateDraft -> activate -> createRevision.
        var connRes = mockMvc.perform(post("/api/v1/provider-connections")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerAccountId\":" + customAccountId
                                + ",\"baseUrl\":\"https://1.1.1.1/v1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long connId = readLong(connRes, "$.id");
        assertThat(awaitMessage("CONN", Long.toString(connId))).isTrue();
        receivedMessages.clear();

        String connKey = GatewayConfigInvalidationPublisher.PREFIX_CONN + connId;
        spyRedis.opsForValue().set(connKey, "{\"cached\":true}");
        mockMvc.perform(put("/api/v1/provider-connections/" + connId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectTimeoutMs\":4000}"))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(connKey)).isFalse();
        assertThat(awaitMessage("CONN", Long.toString(connId))).isTrue();
        receivedMessages.clear();

        spyRedis.opsForValue().set(connKey, "{\"cached\":true}");
        spyRedis.opsForValue().set(routeWildcard, "{\"cached\":true}");
        mockMvc.perform(post("/api/v1/provider-connections/" + connId + "/activate")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(connKey)).isFalse();
        assertThat(spyRedis.hasKey(routeWildcard)).isFalse();
        assertThat(awaitMessage("CONN", Long.toString(connId))).isTrue();
        assertThat(awaitMessage("ROUTE_ORG", Long.toString(orgId))).isTrue();
        receivedMessages.clear();

        var revRes = mockMvc.perform(post("/api/v1/provider-connections/" + connId + "/revisions")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long revId = readLong(revRes, "$.id");
        assertThat(awaitMessage("CONN", Long.toString(revId))).isTrue();
        receivedMessages.clear();

        // ProviderCredential create -> rotate -> revoke: NEVER store provider secrets/keys in Redis!
        var pcredRes = mockMvc.perform(post("/api/v1/provider-connections/" + connId + "/credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSecret\":\"sk-inv-secret-one\",\"safeLabel\":\"v1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long pcred1Id = readLong(pcredRes, "$.id");
        assertThat(awaitMessage("PROVIDER_CRED", orgId + ":" + customAccountId)).isTrue();
        assertThat(awaitMessage("ROUTE_ORG", Long.toString(orgId))).isTrue();
        receivedMessages.clear();

        var rotRes = mockMvc.perform(post("/api/v1/provider-connections/" + connId + "/credentials/rotate")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawSecret\":\"sk-inv-secret-two\",\"safeLabel\":\"v2\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long pcred2Id = readLong(rotRes, "$.id");
        assertThat(awaitMessage("PROVIDER_CRED", orgId + ":" + customAccountId)).isTrue();
        assertThat(awaitMessage("ROUTE_ORG", Long.toString(orgId))).isTrue();
        receivedMessages.clear();

        mockMvc.perform(post("/api/v1/provider-connections/" + connId + "/credentials/" + pcred2Id + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isNoContent());
        assertThat(awaitMessage("PROVIDER_CRED", orgId + ":" + customAccountId)).isTrue();
        assertThat(awaitMessage("ROUTE_ORG", Long.toString(orgId))).isTrue();
        assertThat(spyRedis.keys("*provider*")).isEmpty();
        receivedMessages.clear();
        assertThat(pcred1Id).isPositive();

        // ModelDiscoveryService promote -> DEL + PUBLISH MODEL + ROUTE.
        mockMvc.perform(post("/api/v1/provider-connections/" + connId + "/models/manual")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"inv-promoted-model\"}"))
                .andExpect(status().isCreated());
        long discoveryId = jdbc.queryForObject(
                "SELECT id FROM provider_model_discovery WHERE org_id=? AND provider_connection_profile_id=? AND provider_model_name='inv-promoted-model'",
                Long.class, orgId, connId);
        jdbc.update("""
                UPDATE provider_model_discovery SET last_probe_status='PASS',
                  verified_capabilities_json=CAST('{"capabilities":["CHAT_COMPLETIONS"]}' AS JSON)
                WHERE id=?
                """, discoveryId);
        String modelRedisKey = GatewayConfigInvalidationPublisher.PREFIX_MODEL + orgId;
        spyRedis.opsForValue().set(modelRedisKey, "{\"cached\":true}");

        var promoteRes = mockMvc.perform(
                        post("/api/v1/provider-connections/" + connId + "/models/" + discoveryId + "/promote")
                                .header("Authorization", managerBearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long promotedModelId = readLong(promoteRes, "$.logicalModelId");
        assertThat(spyRedis.hasKey(modelRedisKey)).isFalse();
        assertThat(awaitMessage("MODEL", Long.toString(orgId))).isTrue();
        assertThat(awaitMessage("ROUTE", orgId + ":" + promotedModelId)).isTrue();
    }

    @Test
    void projectTeamCostCenterAndAdvisorProfileMutationsInvalidateAffectedPrincipals() throws Exception {
        long teamId = insertTeam(orgId, "inv-team-" + fixtureCounter);
        long costCenterId = insertCostCenter(orgId, "inv-cc-" + fixtureCounter);
        long svcId = insertServiceIdentity(orgId, "inv-org-svc-" + fixtureCounter);

        long projCredId = insertDummyCred(svcId, projectId, "PROJECT", projectId, "invproj00001");
        long teamCredId = insertDummyCred(svcId, projectId, "TEAM", teamId, "invteam00001");
        long ccCredId = insertDummyCred(svcId, projectId, "COST_CENTER", costCenterId, "invcc0000001");

        String kProj = GatewayConfigInvalidationPublisher.PREFIX_PRINCIPAL + projCredId;
        String kTeam = GatewayConfigInvalidationPublisher.PREFIX_PRINCIPAL + teamCredId;
        String kCc = GatewayConfigInvalidationPublisher.PREFIX_PRINCIPAL + ccCredId;
        spyRedis.opsForValue().set(kProj, "1");
        spyRedis.opsForValue().set(kTeam, "1");
        spyRedis.opsForValue().set(kCc, "1");

        mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Project\"}"))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(kProj)).isFalse();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(projCredId))).isTrue();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(teamCredId))).isTrue();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(ccCredId))).isTrue();
        receivedMessages.clear();

        spyRedis.opsForValue().set(kTeam, "1");
        mockMvc.perform(patch("/api/v1/teams/" + teamId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Team\"}"))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(kTeam)).isFalse();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(teamCredId))).isTrue();
        receivedMessages.clear();

        spyRedis.opsForValue().set(kCc, "1");
        mockMvc.perform(patch("/api/v1/cost-centers/" + costCenterId)
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated CostCenter\"}"))
                .andExpect(status().isOk());
        assertThat(spyRedis.hasKey(kCc)).isFalse();
        assertThat(awaitMessage("PRINCIPAL", Long.toString(ccCredId))).isTrue();
        receivedMessages.clear();

        // Advisor profile update -> rotates internal credential & publishes CRED + PRINCIPAL.
        mockMvc.perform(put("/api/v1/ai-advisor/profile")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "providerModelId":%d,
                                  "projectId":%d,
                                  "financialScopeType":"PROJECT",
                                  "financialScopeId":%d,
                                  "budgetEnforcementMode":"OPTIONAL"
                                }
                                """.formatted(providerModelId, projectId, projectId)))
                .andExpect(status().isOk());
        long advCredId = jdbc.queryForObject("""
                SELECT id FROM gateway_credential
                WHERE org_id=? AND credential_origin='INTERNAL_SYSTEM' AND status='ACTIVE'
                """, Long.class, orgId);
        assertThat(awaitMessage("PRINCIPAL", Long.toString(advCredId))).isTrue();
    }

    @Test
    void rolledBackTransactionNeverInvalidatesAndRedisOutageDoesNotAbortDbCommit() throws Exception {
        // 1. Rolled-back TX MUST NOT delete Redis L2 key or publish Pub/Sub event.
        String modelKey = GatewayConfigInvalidationPublisher.PREFIX_MODEL + orgId;
        spyRedis.opsForValue().set(modelKey, "{\"cached\":true}");
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            invalidations.invalidateModelCatalog(orgId);
            status.setRollbackOnly();
        });
        assertThat(spyRedis.hasKey(modelKey)).isTrue();
        assertThat(receivedMessages.poll(250, TimeUnit.MILLISECONDS)).isNull();

        // 2. Redis outage during AFTER_COMMIT must be swallowed gracefully; DB commit persists!
        long svcId = insertServiceIdentity(orgId, "inv-outage-svc-" + fixtureCounter);
        var credRes = mockMvc.perform(post("/api/v1/gateway-credentials")
                        .header("Authorization", managerBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "principalType":"SERVICE",
                                  "serviceIdentityId":%d,
                                  "projectId":%d,
                                  "financialScopeType":"PROJECT",
                                  "financialScopeId":%d,
                                  "budgetEnforcementMode":"OPTIONAL",
                                  "modelIds":[%d]
                                }
                                """.formatted(svcId, projectId, projectId, modelId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long credId = readLong(credRes, "$.id");

        doThrow(new RedisConnectionFailureException("Simulated Redis outage on DEL"))
                .when(spyRedis).delete(anyString());
        doThrow(new RedisConnectionFailureException("Simulated Redis outage on DEL collection"))
                .when(spyRedis).delete(anyCollection());
        doThrow(new RedisConnectionFailureException("Simulated Redis outage on PUBLISH"))
                .when(spyRedis).convertAndSend(anyString(), any());

        mockMvc.perform(post("/api/v1/gateway-credentials/" + credId + "/revoke")
                        .header("Authorization", managerBearer()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM gateway_credential WHERE id=?", String.class, credId))
                .isEqualTo("REVOKED");
    }

    private boolean awaitMessage(String type, String key) throws InterruptedException {
        var buffered = new ArrayList<InvalidationMessage>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var msg = receivedMessages.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null) {
                if (type.equals(msg.type()) && key.equals(msg.key())) {
                    for (var other : buffered) {
                        receivedMessages.offer(other);
                    }
                    return true;
                }
                buffered.add(msg);
            }
        }
        for (var other : buffered) {
            receivedMessages.offer(other);
        }
        return false;
    }

    private static long readLong(String json, String path) {
        return Long.parseLong(JsonPath.read(json, path).toString());
    }

    private long insertTeam(long org, String code) {
        jdbc.update("""
                INSERT INTO team(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Inv Team','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM team WHERE org_id=? AND code=?", Long.class, org, code);
    }

    private long insertCostCenter(long org, String code) {
        jdbc.update("""
                INSERT INTO cost_center(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Inv Cost Center','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, code);
        return jdbc.queryForObject("SELECT id FROM cost_center WHERE org_id=? AND code=?", Long.class, org, code);
    }

    private long insertDummyCred(
            long serviceIdentityId, long projId, String scopeType, long scopeId, String prefix) {
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,UNHEX(REPEAT('AB',32)),1,'SERVICE',NULL,?,?,?,?,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, prefix, serviceIdentityId, projId, scopeType, scopeId);
        return jdbc.queryForObject(
                "SELECT id FROM gateway_credential WHERE org_id=? AND credential_prefix=?",
                Long.class, orgId, prefix);
    }
}
