package com.aicostops.ledger.application;

import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerPosting;
import com.aicostops.ledger.domain.CorrectionGroup;
import java.util.List;
import java.util.Map;

/** Stable application read models shared by posting and the HTTP boundary. */
public final class LedgerReadModels {

    private LedgerReadModels() {
    }

    public record LedgerPostingDetail(LedgerPosting posting, List<LedgerEntry> entries) {
        public LedgerPostingDetail {
            entries = List.copyOf(entries);
        }
    }

    public record CorrectionResult(CorrectionGroup correctionGroup, LedgerPostingDetail posting) {
    }

    public record LedgerPostingView(LedgerPosting posting, List<LedgerEntry> visibleEntries) {
        public LedgerPostingView {
            visibleEntries = List.copyOf(visibleEntries);
        }
    }

    public record LedgerEntryView(LedgerPosting posting, LedgerEntry entry,
            LedgerLineage lineage, List<LedgerEntry> visiblePostingEntries) {
        public LedgerEntryView {
            visiblePostingEntries = List.copyOf(visiblePostingEntries);
        }
    }

    /** IDs/statuses for the business-source chain; absent branches stay null. */
    public record LedgerLineage(
            Long allocationLineId,
            Long allocationDecisionId,
            String allocationDecisionStatus,
            Long chargeFactId,
            String chargeProviderCode,
            String chargeReviewStatus,
            Long rawProviderRecordId,
            Long importAttemptId,
            Long importBatchId,
            Long providerEvidenceId,
            Long expenseClaimId,
            String expenseStatus,
            Long expenseEvidenceId,
            Long correctionGroupId,
            Long reversesEntryId) {
    }
}
