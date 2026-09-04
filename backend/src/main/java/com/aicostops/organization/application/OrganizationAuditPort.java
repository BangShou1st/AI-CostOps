package com.aicostops.organization.application;

/**
 * Audit port of organization master-data mutations. Implementations must
 * append the audit event inside the caller's transaction so any audit write
 * failure rolls the whole command back. Metadata is limited to stable
 * identifiers and enums; provider credentials and raw metadata must never
 * reach the audit trail.
 */
public interface OrganizationAuditPort {

    /**
     * Appends {@code PROVIDER_ACCOUNT_CREATED} with the subject id set to the
     * provider account id. Metadata carries only provider code and resulting
     * status.
     */
    void providerAccountCreated(long organizationId, long actorUserId,
            long providerAccountId, String providerCode, String status);

    /**
     * Appends {@code PROVIDER_ACCOUNT_UPDATED} with the resulting status.
     */
    void providerAccountUpdated(long organizationId, long actorUserId,
            long providerAccountId, String status);

    /**
     * Appends {@code PROVIDER_ACCOUNT_ARCHIVED} with the previous status, so
     * the transition into ARCHIVED is distinguishable from a plain update.
     */
    void providerAccountArchived(long organizationId, long actorUserId,
            long providerAccountId, String previousStatus);

    void routingPolicyCreated(long organizationId, long actorUserId,
            long policyId, int version, String status);

    void routingPolicyRevised(long organizationId, long actorUserId,
            long policyId, int version, String status);

    void routingPolicyUpdated(long organizationId, long actorUserId,
            long policyId, int version, String status);

    void routingPolicyActivated(long organizationId, long actorUserId,
            long policyId, int version, String status);
}
