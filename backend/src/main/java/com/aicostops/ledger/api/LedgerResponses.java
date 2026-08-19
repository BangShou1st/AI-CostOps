package com.aicostops.ledger.api;

import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingDetail;
import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.shared.json.ApiId;
import java.time.Instant;
import java.util.List;

/** JSON-safe Ledger responses: IDs are strings and money is decimal strings. */
public final class LedgerResponses {

    private LedgerResponses() {
    }

    public record LedgerEntryResponse(
            ApiId id,
            ApiId postingId,
            int entryIndex,
            String entryType,
            String amount,
            String currency,
            String targetType,
            ApiId targetId,
            ApiId budgetId,
            ApiId sourceChargeFactId,
            ApiId sourceExpenseClaimId,
            ApiId allocationLineId,
            ApiId correctionGroupId,
            ApiId reversesEntryId,
            Instant createdAt) {

        static LedgerEntryResponse from(LedgerEntry entry) {
            return new LedgerEntryResponse(ApiId.of(entry.id()), ApiId.of(entry.postingId()),
                    entry.entryIndex(), entry.entryType().name(), entry.amount().toPlainString(),
                    entry.currency(), entry.targetType(), ApiId.of(entry.targetId()),
                    entry.budgetId() == null ? null : ApiId.of(entry.budgetId()),
                    entry.sourceChargeFactId() == null ? null : ApiId.of(entry.sourceChargeFactId()),
                    entry.sourceExpenseClaimId() == null ? null : ApiId.of(entry.sourceExpenseClaimId()),
                    entry.allocationLineId() == null ? null : ApiId.of(entry.allocationLineId()),
                    entry.correctionGroupId() == null ? null : ApiId.of(entry.correctionGroupId()),
                    entry.reversesEntryId() == null ? null : ApiId.of(entry.reversesEntryId()),
                    entry.createdAt());
        }
    }

    public record LedgerPostingDetailResponse(
            ApiId id,
            String postingKey,
            String sourceType,
            ApiId sourceId,
            ApiId allocationDecisionId,
            ApiId billingPeriodId,
            String status,
            ApiId postedByMemberId,
            Instant postedAt,
            Instant createdAt,
            List<LedgerEntryResponse> entries) {

        public static LedgerPostingDetailResponse from(LedgerPostingDetail detail) {
            var posting = detail.posting();
            return new LedgerPostingDetailResponse(ApiId.of(posting.id()), posting.postingKey(),
                    posting.sourceType().name(), ApiId.of(posting.sourceId()),
                    posting.allocationDecisionId() == null ? null : ApiId.of(posting.allocationDecisionId()),
                    ApiId.of(posting.billingPeriodId()), posting.status(),
                    ApiId.of(posting.postedByMemberId()), posting.postedAt(), posting.createdAt(),
                    detail.entries().stream().map(LedgerEntryResponse::from).toList());
        }
    }
}
