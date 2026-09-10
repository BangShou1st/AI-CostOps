package com.aicostops.gatewayadmin.application;

/**
 * Audit port of governed Control Plane mutations (service identity, gateway
 * credential, pricing). Implementations must append the audit event inside the
 * caller's transaction so any audit write failure rolls the whole command
 * back. Metadata is limited to stable identifiers and enums; raw secrets and
 * digests must never reach the audit trail.
 */
public interface GatewayAdminAuditPort {

    void serviceIdentityCreated(long organizationId, long actorUserId,
            long serviceIdentityId, String code, String status);

    void gatewayCredentialCreated(long organizationId, long actorUserId,
            long credentialId, String prefix, String principalType, String status);

    void gatewayCredentialRevoked(long organizationId, long actorUserId,
            long credentialId, String prefix, String previousStatus);

    void pricingVersionCreated(long organizationId, long actorUserId,
            long pricingVersionId, long providerAccountId, long providerModelId,
            int version, String currency, String status);

    void pricingVersionActivated(long organizationId, long actorUserId,
            long pricingVersionId, long providerAccountId, long providerModelId,
            int version);
}