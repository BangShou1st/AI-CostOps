package com.aicostops.ledger.api;

import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingDetail;
import com.aicostops.ledger.application.LedgerReadModels.CorrectionResult;
import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingView;
import com.aicostops.ledger.application.LedgerReadModels.LedgerEntryView;
import com.aicostops.ledger.application.LedgerReadModels.LedgerLineage;
import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.shared.web.PageResponse;
import java.math.BigDecimal;
import java.util.Map;
import java.util.LinkedHashMap;
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

    public record LedgerCorrectionResponse(
            ApiId correctionGroupId,
            LedgerPostingDetailResponse posting) {

        static LedgerCorrectionResponse from(CorrectionResult result) {
            return new LedgerCorrectionResponse(ApiId.of(result.correctionGroup().id()),
                    LedgerPostingDetailResponse.from(result.posting()));
        }
    }

    public record LedgerPostingSummaryResponse(
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
            int visibleEntryCount,
            String visibleTotalAmount,
            String visibleCurrency,
            Map<String, String> visibleTotals,
            List<LedgerEntryResponse> entries) {

        static LedgerPostingSummaryResponse from(LedgerPostingView view) {
            var posting = view.posting();
            var totals = new LinkedHashMap<String, BigDecimal>();
            for (var entry : view.visibleEntries()) {
                totals.merge(entry.currency(), entry.amount(), BigDecimal::add);
            }
            var visibleCurrency = totals.size() == 1 ? totals.keySet().iterator().next() : null;
            var visibleAmount = totals.size() == 1
                    ? totals.values().iterator().next().toPlainString() : null;
            var totalStrings = totals.entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            item -> item.getValue().toPlainString(), (left, right) -> right,
                            LinkedHashMap::new));
            return new LedgerPostingSummaryResponse(ApiId.of(posting.id()), posting.postingKey(),
                    posting.sourceType().name(), ApiId.of(posting.sourceId()),
                    posting.allocationDecisionId() == null ? null : ApiId.of(posting.allocationDecisionId()),
                    ApiId.of(posting.billingPeriodId()), posting.status(),
                    ApiId.of(posting.postedByMemberId()), posting.postedAt(), posting.createdAt(),
                    view.visibleEntries().size(), visibleAmount, visibleCurrency, totalStrings,
                    view.visibleEntries().stream().map(LedgerEntryResponse::from).toList());
        }
    }

    public record LedgerEntryDetailResponse(
            LedgerEntryResponse entry,
            LedgerPostingSummaryResponse posting,
            LedgerLineageResponse lineage) {

        static LedgerEntryDetailResponse from(LedgerEntryView view) {
            return new LedgerEntryDetailResponse(LedgerEntryResponse.from(view.entry()),
                    LedgerPostingSummaryResponse.from(new LedgerPostingView(view.posting(),
                            view.visiblePostingEntries())), LedgerLineageResponse.from(view.lineage()));
        }
    }

    public record LedgerLineageResponse(
            ApiId allocationLineId,
            ApiId allocationDecisionId,
            String allocationDecisionStatus,
            ApiId chargeFactId,
            String chargeProviderCode,
            String chargeReviewStatus,
            ApiId rawProviderRecordId,
            ApiId importAttemptId,
            ApiId importBatchId,
            ApiId providerEvidenceId,
            ApiId expenseClaimId,
            String expenseStatus,
            ApiId expenseEvidenceId,
            ApiId correctionGroupId,
            ApiId reversesEntryId,
            ApiId correctedByCorrectionGroupId,
            ApiId correctionTargetEntryId) {

        static LedgerLineageResponse from(LedgerLineage lineage) {
            return new LedgerLineageResponse(id(lineage.allocationLineId()),
                    id(lineage.allocationDecisionId()), lineage.allocationDecisionStatus(),
                    id(lineage.chargeFactId()), lineage.chargeProviderCode(),
                    lineage.chargeReviewStatus(), id(lineage.rawProviderRecordId()),
                    id(lineage.importAttemptId()), id(lineage.importBatchId()),
                    id(lineage.providerEvidenceId()), id(lineage.expenseClaimId()),
                    lineage.expenseStatus(), id(lineage.expenseEvidenceId()),
                    id(lineage.correctionGroupId()), id(lineage.reversesEntryId()),
                    id(lineage.correctedByCorrectionGroupId()), id(lineage.correctionTargetEntryId()));
        }
    }

    static PageResponse<LedgerPostingSummaryResponse> postingPage(
            PageResponse<LedgerPostingView> page) {
        return new PageResponse<>(page.items().stream().map(LedgerPostingSummaryResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    static PageResponse<LedgerEntryResponse> entryPage(PageResponse<LedgerEntry> page) {
        return new PageResponse<>(page.items().stream().map(LedgerEntryResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    private static ApiId id(Long value) {
        return value == null ? null : ApiId.of(value);
    }
}
