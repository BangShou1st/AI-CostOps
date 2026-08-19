package com.aicostops.ledger.application;

import com.aicostops.ledger.domain.CorrectionMode;
import java.math.BigDecimal;
import java.util.List;

/** Commands accepted by Ledger source-posting workflows. */
public final class LedgerPostingCommands {

    private LedgerPostingCommands() {
    }

    public record CommitmentLink(long allocationLineId, long commitmentId) {
    }

    public record PostSourceCommand(List<CommitmentLink> commitmentLinks) {
        public PostSourceCommand {
            commitmentLinks = commitmentLinks == null ? List.of() : List.copyOf(commitmentLinks);
        }
    }

    public record CorrectionCommand(
            long targetEntryId,
            long correctionPeriodId,
            CorrectionMode mode,
            String reasonCode,
            String reasonText,
            Replacement replacement) {

        public record Replacement(
                BigDecimal amount,
                String currency,
                Long projectId,
                Long costCenterId,
                Long teamId) {
        }
    }
}
