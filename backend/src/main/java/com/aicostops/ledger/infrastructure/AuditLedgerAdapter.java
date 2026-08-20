package com.aicostops.ledger.infrastructure;

import com.aicostops.audit.application.AuditService;
import com.aicostops.ledger.application.LedgerAuditPort;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/** Secret-free audit adapter for Ledger posting events. */
@Component
public class AuditLedgerAdapter implements LedgerAuditPort {

    private final AuditService auditService;

    public AuditLedgerAdapter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void chargePosted(long organizationId, long actorUserId, long postingId,
            long chargeFactId, long allocationDecisionId, int entryCount, String currency) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("chargeFactId", Long.toString(chargeFactId));
        metadata.put("allocationDecisionId", Long.toString(allocationDecisionId));
        metadata.put("entryCount", entryCount);
        metadata.put("currency", currency);
        auditService.append("LEDGER_CHARGE_POSTED", organizationId, actorUserId,
                "LEDGER_POSTING", postingId, metadata);
    }

    @Override
    public void expensePosted(long organizationId, long actorUserId, long postingId,
            long expenseClaimId, long allocationDecisionId, int entryCount, String currency) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("expenseClaimId", Long.toString(expenseClaimId));
        metadata.put("allocationDecisionId", Long.toString(allocationDecisionId));
        metadata.put("entryCount", entryCount);
        metadata.put("currency", currency);
        auditService.append("LEDGER_EXPENSE_POSTED", organizationId, actorUserId,
                "LEDGER_POSTING", postingId, metadata);
    }

    @Override
    public void correctionPosted(long organizationId, long actorUserId, long postingId,
            long correctionGroupId, long targetEntryId, String mode, int entryCount,
            String currency) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("correctionGroupId", Long.toString(correctionGroupId));
        metadata.put("targetEntryId", Long.toString(targetEntryId));
        metadata.put("mode", mode);
        metadata.put("entryCount", entryCount);
        metadata.put("currency", currency);
        auditService.append("LEDGER_CORRECTION_POSTED", organizationId, actorUserId,
                "LEDGER_POSTING", postingId, metadata);
    }
}
