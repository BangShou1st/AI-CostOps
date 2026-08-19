package com.aicostops.ledger.application;

import com.aicostops.ledger.domain.LedgerEntry;
import com.aicostops.ledger.domain.LedgerPosting;
import java.util.List;

/** Stable application read models shared by posting and the HTTP boundary. */
public final class LedgerReadModels {

    private LedgerReadModels() {
    }

    public record LedgerPostingDetail(LedgerPosting posting, List<LedgerEntry> entries) {
        public LedgerPostingDetail {
            entries = List.copyOf(entries);
        }
    }
}
