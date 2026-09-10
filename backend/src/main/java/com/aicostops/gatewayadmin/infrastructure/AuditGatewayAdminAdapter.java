package com.aicostops.gatewayadmin.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.gatewayadmin.application.GatewayAdminAuditPort;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Governed Control Plane audit adapter delegating to the shared AuditService. */
@Component
public class AuditGatewayAdminAdapter implements GatewayAdminAuditPort {

    private final AuditService auditService;

    public AuditGatewayAdminAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void serviceIdentityCreated(long organizationId, long actorUserId,
            long serviceIdentityId, String code, String status) {
        auditService.append("SERVICE_IDENTITY_CREATED", organizationId, actorUserId,
                "SERVICE_IDENTITY", serviceIdentityId, Map.of("code", code, "status", status));
    }

    @Override
    public void gatewayCredentialCreated(long organizationId, long actorUserId,
            long credentialId, String prefix, String principalType, String status) {
        auditService.append("GATEWAY_CREDENTIAL_CREATED", organizationId, actorUserId,
                "GATEWAY_CREDENTIAL", credentialId,
                Map.of("prefix", prefix, "principalType", principalType, "status", status));
    }

    @Override
    public void gatewayCredentialRevoked(long organizationId, long actorUserId,
            long credentialId, String prefix, String previousStatus) {
        auditService.append("GATEWAY_CREDENTIAL_REVOKED", organizationId, actorUserId,
                "GATEWAY_CREDENTIAL", credentialId,
                Map.of("prefix", prefix, "previousStatus", previousStatus));
    }

    @Override
    public void pricingVersionCreated(long organizationId, long actorUserId,
            long pricingVersionId, long providerAccountId, long providerModelId,
            int version, String currency, String status) {
        auditService.append("PRICING_VERSION_CREATED", organizationId, actorUserId,
                "PRICING_VERSION", pricingVersionId,
                Map.of("providerAccountId", Long.toString(providerAccountId),
                        "providerModelId", Long.toString(providerModelId),
                        "version", Integer.toString(version), "currency", currency, "status", status));
    }

    @Override
    public void pricingVersionActivated(long organizationId, long actorUserId,
            long pricingVersionId, long providerAccountId, long providerModelId, int version) {
        auditService.append("PRICING_VERSION_ACTIVATED", organizationId, actorUserId,
                "PRICING_VERSION", pricingVersionId,
                Map.of("providerAccountId", Long.toString(providerAccountId),
                        "providerModelId", Long.toString(providerModelId),
                        "version", Integer.toString(version)));
    }
}