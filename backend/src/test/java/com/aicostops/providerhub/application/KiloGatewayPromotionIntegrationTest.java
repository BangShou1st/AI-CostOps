package com.aicostops.providerhub.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.providerhub.infrastructure.ModelDiscoveryMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Kilo Gateway model registration -&gt; simulated probe -&gt; promotion over real MySQL (2026-10-18).
 *
 * <p>Kilo Provider ids legitimately contain {@code /} and {@code :} (for example
 * {@code poolside/laguna-s-2.1:free}), which the generic logical model_key rule rejects. Promotion
 * must still work: a stable, legal, collision-resistant internal key is derived for the built-in
 * Kilo template while {@code provider_model.provider_model_name} keeps the exact raw id. Other
 * Providers keep the existing raw-key behaviour.
 */
@SpringBootTest(properties = {
        "aicostops.auth.jwt-signing-secret=" + ControlPlaneFixtureSupport.JWT_SECRET,
        "aicostops.gateway.provider-kek-v1=" + ControlPlaneFixtureSupport.TEST_KEK })
@Import(ControlPlaneFixtureSupport.LenientControlPlane.class)
@Tag("integration")
class KiloGatewayPromotionIntegrationTest extends ControlPlaneFixtureSupport {

    private static final String FREE_MODEL = "poolside/laguna-s-2.1:free";
    private static final String PAID_MODEL = "deepseek-v4-pro";
    private static final String ROLE = "KILO_PROMOTE_MANAGER";

    @Autowired
    private ModelDiscoveryService discovery;

    @Autowired
    private ModelDiscoveryMapper discoveryMapper;

    private long organizationId;
    private long managerUserId;
    private long kiloAccountId;
    private long kiloProfileId;
    private long customAccountId;
    private long customProfileId;

    @BeforeEach
    void setUp() {
        flushRedis();
        cleanDatabase();
        // Full-suite order wipes provider_catalog between classes; reseed both rows this fixture binds to.
        reseedCustomCatalog();
        seedKiloCatalog();
        organizationId = insertOrganization("Kilo Promote Org", "kilo-promote-org");
        managerUserId = insertUser("kilo-promote@example.com");
        var managerMemberId = insertMember(organizationId, managerUserId);
        createPermissionRole(ROLE, List.of(
                "PROVIDER_ACCOUNT_MANAGE", "PROVIDER_ACCOUNT_READ"));
        assign(managerMemberId, ROLE, "ORG", organizationId);
        kiloAccountId = insertAccount(organizationId, "KILO_GATEWAY", "Kilo Account");
        kiloProfileId = insertActiveProfile(kiloAccountId, "BUILTIN", "KILO_GATEWAY",
                ProviderTemplateRegistry.KILO_GATEWAY_BASE_URL, "BEARER", "DIRECT_ONLY");
        customAccountId = insertAccount(organizationId, "CUSTOM_OPENAI_COMPATIBLE", "Custom Account");
        customProfileId = insertActiveProfile(customAccountId, "CUSTOM", null,
                "https://custom.example.invalid/v1", "BEARER", "DIRECT_PUBLIC_ONLY");
        flushRedis();
    }

    /**
     * The MySQL container is shared across every integration class in the JVM, and the exact
     * documented role catalog is asserted elsewhere. Leaving the fixture role behind would pollute
     * that assertion, so the suite cleans up after itself as well as before.
     */
    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void freeKiloModelWithSlashAndColonPromotesToAStableLegalKey() {
        var user = new AuthenticatedUser(managerUserId, 7);

        var observation = discovery.registerManual(user, kiloProfileId, FREE_MODEL);
        assertThat(observation.providerModelName()).isEqualTo(FREE_MODEL);
        assertThat(observation.protocolCode())
                .isEqualTo(ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS);
        assertThat(observation.pricingClassification())
                .isEqualTo(KiloGatewayModelManifest.PRICING_VERIFIED_FREE);

        simulateSuccessfulChatProbe(observation.id());

        var promotion = discovery.promote(user, kiloProfileId, observation.id());

        // The derived logical key must be legal even though the raw id carries "/" and ":".
        var key = jdbc.queryForObject("SELECT model_key FROM model_catalog WHERE id=?",
                String.class, promotion.logicalModelId());
        assertThat(key).matches("[a-z0-9][a-z0-9._-]{0,99}");
        assertThat(key).doesNotContain("/").doesNotContain(":");
        assertThat(key).startsWith("kilo-");

        // The raw id stays the logical display name and the exact Provider Model wire name.
        assertThat(jdbc.queryForObject("SELECT name FROM model_catalog WHERE id=?",
                String.class, promotion.logicalModelId())).isEqualTo(FREE_MODEL);
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM model_catalog WHERE id=?",
                Long.class, promotion.logicalModelId())).isEqualTo(organizationId);
        assertThat(jdbc.queryForObject(
                "SELECT provider_model_name FROM provider_model WHERE id=?",
                String.class, promotion.providerModelId())).isEqualTo(FREE_MODEL);
        assertThat(jdbc.queryForObject("SELECT model_id FROM provider_model WHERE id=?",
                Long.class, promotion.providerModelId())).isEqualTo(promotion.logicalModelId());
        assertThat(jdbc.queryForObject("SELECT provider_code FROM provider_model WHERE id=?",
                String.class, promotion.providerModelId()))
                .isEqualTo(ProviderTemplateRegistry.KILO_GATEWAY);
        assertThat(jdbc.queryForObject("SELECT provider_account_id FROM provider_model WHERE id=?",
                Long.class, promotion.providerModelId())).isEqualTo(kiloAccountId);
    }

    @Test
    void nonFreeKiloModelIsStillNotPromotable() {
        var user = new AuthenticatedUser(managerUserId, 7);
        var observation = discovery.registerManual(user, kiloProfileId, PAID_MODEL);
        simulateSuccessfulChatProbe(observation.id());

        assertThatThrownBy(() -> discovery.promote(user, kiloProfileId, observation.id()))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).code())
                        .isEqualTo(ProblemCode.MODEL_NOT_VERIFIED));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_catalog WHERE name=?",
                Integer.class, PAID_MODEL)).isZero();
    }

    @Test
    void otherProvidersKeepTheRawKeyBehaviour() {
        var user = new AuthenticatedUser(managerUserId, 7);
        var observation = discovery.registerManual(user, customProfileId, "custom-model-1");
        simulateSuccessfulChatProbe(observation.id());

        var promotion = discovery.promote(user, customProfileId, observation.id());
        assertThat(jdbc.queryForObject("SELECT model_key FROM model_catalog WHERE id=?",
                String.class, promotion.logicalModelId())).isEqualTo("custom-model-1");
    }

    /** Records a successful Chat probe observation without any Provider HTTP I/O. */
    private void simulateSuccessfulChatProbe(long discoveryId) {
        var updated = discoveryMapper.recordProbe(discoveryId, organizationId,
                "{\"capabilities\": [\"CHAT_COMPLETIONS\"]}", "PASS", null, Instant.now());
        assertThat(updated).isOne();
    }

    private void seedKiloCatalog() {
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('KILO_GATEWAY','Kilo Gateway',"
                + "'KILO_GATEWAY','https://ai-gateway.kilo.ai/api/v1','ACTIVE',JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE adapter_code='KILO_GATEWAY',status='ACTIVE',"
                + "updated_at=UTC_TIMESTAMP(6)");
    }

    /** FK-safe truncation of the rows this suite creates, in dependency order. */
    private void cleanDatabase() {
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM provider_model_discovery");
        jdbc.update("DELETE FROM provider_connection_profile");
        jdbc.update("UPDATE provider_credential SET predecessor_credential_id=NULL");
        jdbc.update("DELETE FROM provider_credential");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM provider_model");
        jdbc.update("DELETE FROM model_catalog");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE FROM provider_catalog");
        jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id"
                + " WHERE r.code=?", ROLE);
        jdbc.update("DELETE FROM `role` WHERE code=?", ROLE);
    }

    private long insertActiveProfile(long accountId, String kind, String templateCode, String baseUrl,
            String authType, String networkPolicy) {
        jdbc.update("INSERT INTO provider_connection_profile(org_id,provider_account_id,version,"
                + "connection_kind,template_code,protocol_code,base_url,completion_path,models_path,"
                + "auth_type,auth_header_name,network_policy,user_agent,connect_timeout_ms,"
                + "response_timeout_ms,status,created_by,created_at,activated_at,retired_at)"
                + " VALUES (?,?,1,?,?,?,?,'/chat/completions','/models',?,NULL,?,NULL,5000,60000,"
                + "'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)",
                organizationId, accountId, kind, templateCode,
                ProviderTemplateRegistry.PROTOCOL_OPENAI_CHAT_COMPLETIONS, baseUrl, authType,
                networkPolicy);
        return jdbc.queryForObject("SELECT id FROM provider_connection_profile"
                + " WHERE org_id=? AND provider_account_id=?", Long.class, organizationId, accountId);
    }
}
