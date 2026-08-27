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
}