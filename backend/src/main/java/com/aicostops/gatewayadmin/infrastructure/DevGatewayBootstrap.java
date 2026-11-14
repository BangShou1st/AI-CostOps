package com.aicostops.gatewayadmin.infrastructure;

import com.aicostops.gatewayadmin.security.GatewayKeyCodec;
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
 * {@code AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true}. It idempotently
 * provisions one SERVICE identity, one OPTIONAL Gateway credential and the explicit
 * {@code default-chat} model relation. Provider accounts, connections, pricing and routing are
 * configured through the Provider Hub so no vendor-specific defaults live in development code.
 *
 * <p>Never logs or persists raw Gateway keys or raw Provider secrets: only
 * the credential prefix and keyed digest are stored.
 */
@Component
@Profile("dev")
public class DevGatewayBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevGatewayBootstrap.class);

    static final String SERVICE_CODE = "aicostops-gateway-dev";
    static final String PROJECT_CODE = "aicostops-gateway-dev";
    static final String MODEL_KEY = "default-chat";

    private final OrganizationMapper organizationMapper;
    private final IamMapper iamMapper;
    private final DevAuthenticationBootstrapMapper devAuthenticationBootstrapMapper;
    private final GatewayAdminMapper gatewayAdminMapper;
    private final Clock clock;
    private final boolean bootstrapEnabled;
    private final String rawGatewayKey;
    private final String credentialHmacKey;
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
            @Value("${aicostops.auth.public-registration-org-slug:local-dev}") String organizationSlug) {
        this.organizationMapper = organizationMapper;
        this.iamMapper = iamMapper;
        this.devAuthenticationBootstrapMapper = devAuthenticationBootstrapMapper;
        this.gatewayAdminMapper = gatewayAdminMapper;
        this.clock = clock;
        this.bootstrapEnabled = bootstrapEnabled;
        this.rawGatewayKey = rawGatewayKey;
        this.credentialHmacKey = credentialHmacKey;
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

        log.info(
                "M14 gateway dev bootstrap ready: org={} credentialId={} serviceIdentityId={} "
                        + "projectId={} modelKey={}; configure Provider Hub connections separately",
                organizationId, credentialId, serviceIdentityId, projectId, MODEL_KEY);
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

    private static long require(Long value, String what) {
        if (value == null) {
            throw new IllegalStateException("Dev bootstrap could not resolve " + what);
        }
        return value;
    }
}
