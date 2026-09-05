package com.aicostops.organization.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.organization.application.OrganizationAuditPort;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Organization master-data audit adapter delegating to the shared AuditService. */
@Component
public class AuditOrganizationAdapter implements OrganizationAuditPort {

    private final AuditService auditService;

    public AuditOrganizationAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void providerAccountCreated(long organizationId, long actorUserId,
            long providerAccountId, String providerCode, String status) {
        auditService.append("PROVIDER_ACCOUNT_CREATED", organizationId, actorUserId,
                "PROVIDER_ACCOUNT", providerAccountId,
                Map.of("providerCode", providerCode, "status", status));
    }

    @Override
    public void providerAccountUpdated(long organizationId, long actorUserId,
            long providerAccountId, String status) {
        auditService.append("PROVIDER_ACCOUNT_UPDATED", organizationId, actorUserId,
                "PROVIDER_ACCOUNT", providerAccountId, Map.of("status", status));
    }

    @Override
    public void providerAccountArchived(long organizationId, long actorUserId,
            long providerAccountId, String previousStatus) {
        auditService.append("PROVIDER_ACCOUNT_ARCHIVED", organizationId, actorUserId,
                "PROVIDER_ACCOUNT", providerAccountId,
                Map.of("previousStatus", previousStatus));
    }

    @Override
    public void routingPolicyCreated(long organizationId, long actorUserId,
            long policyId, int version, String status) {
        appendRoutingPolicy("ROUTING_POLICY_CREATED", organizationId, actorUserId, policyId, version, status);
    }

    @Override
    public void routingPolicyRevised(long organizationId, long actorUserId,
            long policyId, int version, String status) {
        appendRoutingPolicy("ROUTING_POLICY_REVISED", organizationId, actorUserId, policyId, version, status);
    }

    @Override
    public void routingPolicyUpdated(long organizationId, long actorUserId,
            long policyId, int version, String status) {
        appendRoutingPolicy("ROUTING_POLICY_UPDATED", organizationId, actorUserId, policyId, version, status);
    }

    @Override
    public void routingPolicyActivated(long organizationId, long actorUserId,
            long policyId, int version, String status) {
        appendRoutingPolicy("ROUTING_POLICY_ACTIVATED", organizationId, actorUserId, policyId, version, status);
    }

    private void appendRoutingPolicy(String eventType, long organizationId, long actorUserId,
            long policyId, int version, String status) {
        auditService.append(eventType, organizationId, actorUserId,
                "ROUTING_POLICY", policyId,
                Map.of("version", version, "status", status));
    }
}
