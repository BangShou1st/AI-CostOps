package com.aicostops.gatewayadmin.infrastructure;

import com.aicostops.gatewayadmin.security.GatewayKeyCodec;
import com.aicostops.gatewayadmin.security.ProviderCredentialEncryptor;
import com.aicostops.iam.infrastructure.DevAuthenticationBootstrapMapper;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.organization.infrastructure.OrganizationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local development provisioning for the M14 Gateway runtime projection.
 *
 * <p>Runs only in the {@code dev} profile and only when
 * {@code AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true}. It idempotently provisions one SERVICE
 * identity, one OPTIONAL Gateway credential, the explicit {@code default-chat} model relation,
 * and one generic custom OpenAI-compatible Provider route. The route is vendor-neutral and exists
 * so local development and browser E2E have a governed routing policy without hard-coding a real
 * Provider integration.
 *
 * <p>Never logs or persists raw Gateway keys or raw Provider secrets: only the credential prefix
 * and keyed digest are stored.
 */
@Component
@Profile("dev")
public class DevGatewayBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevGatewayBootstrap.class);

    static final String SERVICE_CODE = "aicostops-gateway-dev";
    static final String PROJECT_CODE = "aicostops-gateway-dev";
    static final String MODEL_KEY = "default-chat";
    static final String PROVIDER_CODE = "CUSTOM_OPENAI_COMPATIBLE";
    static final String PROVIDER_MODEL_NAME = "dev-chat-model";
    static final String DEFAULT_PROVIDER_BASE_URL = "https://provider.example.test/v1";

    private final OrganizationMapper organizationMapper;
    private final IamMapper iamMapper;
    private final DevAuthenticationBootstrapMapper devAuthenticationBootstrapMapper;
    private final GatewayAdminMapper gatewayAdminMapper;
    private final Clock clock;
    private final boolean bootstrapEnabled;
    private final String rawGatewayKey;
    private final String credentialHmacKey;
    private final String providerKek;
    private final String providerBaseUrl;
    private final String providerApiKey;
    private final String organizationSlug;

    public DevGatewayBootstrap(
            OrganizationMapper organizationMapper,
            IamMapper iamMapper,
            DevAuthenticationBootstrapMapper devAuthenticationBootstrapMapper,
            GatewayAdminMapper gatewayAdminMapper,
            Clock clock,
            @Value("${aicostops.gateway.dev-bootstrap-enabled:false}") boolean bootstrapEnabled,
            @Value("${aicostops.gateway.dev-raw-key:}") String rawGatewayKey,
            @Value("${aicostops.gateway.credential-hmac-key-v1:}") String credentialHmacKey,
            @Value("${aicostops.gateway.provider-kek-v1:}") String providerKek,
            @Value("${AICOSTOPS_DEV_PROVIDER_BASE_URL:https://provider.example.test/v1}") String providerBaseUrl,
            @Value("${AICOSTOPS_DEV_PROVIDER_API_KEY:dev-only-provider-token}") String providerApiKey,
            @Value("${aicostops.auth.public-registration-org-slug:local-dev}") String organizationSlug) {
        this.organizationMapper = organizationMapper;
        this.iamMapper = iamMapper;
        this.devAuthenticationBootstrapMapper = devAuthenticationBootstrapMapper;
        this.gatewayAdminMapper = gatewayAdminMapper;
        this.clock = clock;
        this.bootstrapEnabled = bootstrapEnabled;
        this.rawGatewayKey = rawGatewayKey;
        this.credentialHmacKey = credentialHmacKey;
        this.providerKek = providerKek == null ? "" : providerKek.trim();
        this.providerBaseUrl = providerBaseUrl == null || providerBaseUrl.isBlank()
                ? DEFAULT_PROVIDER_BASE_URL : providerBaseUrl.trim();
        this.providerApiKey = providerApiKey == null ? "" : providerApiKey.trim();
        this.organizationSlug = organizationSlug;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!bootstrapEnabled) {
            return;
        }
        var parsedKey = GatewayKeyCodec.parse(rawGatewayKey);
        var now = Instant.now(clock);

        organizationMapper.insertActiveOrganizationIfMissing(
                organizationSlug, "AI CostOps Local Development");
        var organizationId = iamMapper.findActiveOrganizationIdBySlug(organizationSlug);
        if (organizationId == null) {
            throw new IllegalStateException("Development bootstrap organization is unavailable");
        }
        var periodStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay();
        var periodEnd = periodStart.toLocalDate().plusMonths(1).atStartOfDay();
        devAuthenticationBootstrapMapper.insertOpenBillingPeriodIfMissing(
                organizationId, periodStart, periodEnd, now);

        var projectId = ensureProject(organizationId);
        var serviceIdentityId = ensureServiceIdentity(organizationId);
        var credentialId = ensureGatewayCredential(
                organizationId, parsedKey, serviceIdentityId, projectId);
        var modelId = ensureModelCatalog();
        gatewayAdminMapper.insertCredentialModelIfMissing(credentialId, organizationId, modelId);

        var providerModelId = ensureProviderModel(modelId);
        var providerAccountId = ensureProviderAccount(organizationId);
        ensureConnectionProfile(organizationId, providerAccountId);
        ensurePricingVersion(organizationId, providerAccountId, providerModelId);
        ensureProviderCredential(organizationId, providerAccountId);
        ensureRoutingPolicy(organizationId, modelId, providerAccountId, providerModelId);

        log.info(
                "M14 gateway dev bootstrap ready: org={} credentialId={} serviceIdentityId={} "
                        + "projectId={} modelKey={} providerCode={} providerModelName={}",
                organizationId, credentialId, serviceIdentityId, projectId, MODEL_KEY,
                PROVIDER_CODE, PROVIDER_MODEL_NAME);
    }

    private long ensureProject(long organizationId) {
        var existing = gatewayAdminMapper.findActiveProjectId(organizationId, PROJECT_CODE);
        if (existing != null) {
            return existing;
        }
        gatewayAdminMapper.insertActiveProject(organizationId, PROJECT_CODE);
        var id = gatewayAdminMapper.findActiveProjectId(organizationId, PROJECT_CODE);
        return require(id, "project " + PROJECT_CODE);
    }

    private long ensureServiceIdentity(long organizationId) {
        var existing = gatewayAdminMapper.findServiceIdentityId(organizationId, SERVICE_CODE);
        if (existing != null) {
            return existing;
        }
        gatewayAdminMapper.insertServiceIdentity(organizationId, SERVICE_CODE);
        var id = gatewayAdminMapper.findServiceIdentityId(organizationId, SERVICE_CODE);
        return require(id, "service identity " + SERVICE_CODE);
    }

    private long ensureGatewayCredential(long organizationId, GatewayKeyCodec.ParsedKey key,
            long serviceIdentityId, long projectId) {
        var existing = gatewayAdminMapper.findCredentialIdByPrefix(key.prefix());
        if (existing != null) {
            return existing;
        }
        var digest = GatewayKeyCodec.digestSecret(key.secretPart(), credentialHmacKey);
        gatewayAdminMapper.insertGatewayCredential(
                organizationId, key.prefix(), digest, serviceIdentityId, projectId);
        var id = gatewayAdminMapper.findCredentialIdByPrefix(key.prefix());
        return require(id, "gateway credential with prefix " + key.prefix());
    }

    private long ensureModelCatalog() {
        var existing = gatewayAdminMapper.findModelIdByKey(MODEL_KEY);
        if (existing != null) {
            return existing;
        }
        gatewayAdminMapper.insertModelCatalog(MODEL_KEY);
        var id = gatewayAdminMapper.findModelIdByKey(MODEL_KEY);
        return require(id, "model catalog " + MODEL_KEY);
    }

    private long ensureProviderModel(long modelId) {
        var existing = gatewayAdminMapper.findProviderModelId(PROVIDER_CODE, PROVIDER_MODEL_NAME);
        if (existing != null) {
            return existing;
        }
        gatewayAdminMapper.insertProviderCatalog(
                PROVIDER_CODE, "Custom OpenAI-Compatible", PROVIDER_CODE, providerBaseUrl);
        gatewayAdminMapper.insertProviderModel(PROVIDER_CODE, modelId, PROVIDER_MODEL_NAME);
        var id = gatewayAdminMapper.findProviderModelId(PROVIDER_CODE, PROVIDER_MODEL_NAME);
        return require(id, "provider model " + PROVIDER_CODE + "/" + PROVIDER_MODEL_NAME);
    }

    private long ensureProviderAccount(long organizationId) {
        var existing = gatewayAdminMapper.findActiveProviderAccountId(organizationId, PROVIDER_CODE);
        if (existing != null) {
            return existing;
        }
        gatewayAdminMapper.insertProviderAccount(
                organizationId, PROVIDER_CODE, "Development Custom Provider", "dev-custom-provider");
        var id = gatewayAdminMapper.findActiveProviderAccountId(organizationId, PROVIDER_CODE);
        return require(id, "provider account " + PROVIDER_CODE);
    }

    private void ensureConnectionProfile(long organizationId, long providerAccountId) {
        if (gatewayAdminMapper.findActiveConnectionProfileId(organizationId, providerAccountId) != null) {
            return;
        }
        gatewayAdminMapper.insertActiveConnectionProfile(
                organizationId, providerAccountId, providerBaseUrl);
    }

    private void ensurePricingVersion(long organizationId, long providerAccountId, long providerModelId) {
        var existing = gatewayAdminMapper.findPricingVersionId(
                organizationId, providerAccountId, providerModelId, 1);
        long pricingVersionId;
        if (existing != null) {
            pricingVersionId = existing;
        } else {
            var range = gatewayAdminMapper.findOpenBillingPeriodRange(organizationId);
            var effectiveFrom = ((java.time.LocalDateTime) range.get("period_start"))
                    .toInstant(java.time.ZoneOffset.UTC);
            var effectiveTo = ((java.time.LocalDateTime) range.get("period_end"))
                    .toInstant(java.time.ZoneOffset.UTC);
            gatewayAdminMapper.insertPricingVersion(
                    organizationId, providerAccountId, providerModelId, 1, effectiveFrom, effectiveTo);
            pricingVersionId = require(
                    gatewayAdminMapper.findPricingVersionId(
                            organizationId, providerAccountId, providerModelId, 1),
                    "pricing version");
        }
        gatewayAdminMapper.insertPricingRateIfMissing(
                organizationId, pricingVersionId, "INPUT_TOKEN", 1_000_000L, "30.00000000");
        gatewayAdminMapper.insertPricingRateIfMissing(
                organizationId, pricingVersionId, "OUTPUT_TOKEN", 1_000_000L, "60.00000000");
        gatewayAdminMapper.insertPricingRateIfMissing(
                organizationId, pricingVersionId, "REQUEST", 1L, "0.00010000");
    }

    private void ensureProviderCredential(long organizationId, long providerAccountId) {
        if (providerKek.isBlank() || providerApiKey.isBlank()
                || gatewayAdminMapper.findActiveProviderCredentialId(
                        organizationId, providerAccountId) != null) {
            return;
        }
        var encryptor = new ProviderCredentialEncryptor(providerKek);
        var encrypted = encryptor.encrypt(
                providerApiKey, organizationId, providerAccountId, "API_KEY", (short) 1);
        gatewayAdminMapper.insertProviderCredential(
                organizationId, providerAccountId, encrypted.ciphertext(), encrypted.nonce());
    }

    private void ensureRoutingPolicy(long organizationId, long modelId,
            long providerAccountId, long providerModelId) {
        var policyId = gatewayAdminMapper.findActiveOrganizationRoutingPolicyId(organizationId, modelId);
        if (policyId == null) {
            gatewayAdminMapper.insertActiveOrganizationRoutingPolicy(organizationId, modelId);
            policyId = require(gatewayAdminMapper.findActiveOrganizationRoutingPolicyId(
                    organizationId, modelId), "organization routing policy");
        }
        gatewayAdminMapper.insertRoutingPolicyCandidateIfMissing(
                organizationId, policyId, providerAccountId, providerModelId);
    }

    private static long require(Long value, String what) {
        if (value == null) {
            throw new IllegalStateException("Dev bootstrap could not resolve " + what);
        }
        return value;
    }
}
