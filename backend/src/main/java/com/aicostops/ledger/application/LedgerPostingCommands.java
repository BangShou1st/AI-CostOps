package com.aicostops.ledger.application;

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
}
