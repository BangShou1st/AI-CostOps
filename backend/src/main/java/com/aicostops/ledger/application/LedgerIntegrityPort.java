package com.aicostops.ledger.application;

import java.util.List;

public interface LedgerIntegrityPort {
    LedgerIntegritySnapshot inspect(long organizationId, long billingPeriodId);
    List<Long> sampleProblemPostingIds(long organizationId, long billingPeriodId, int limit);

    record LedgerIntegritySnapshot(
            long postingsWithoutEntries,
            long normalEntryMismatches,
            long normalPostingCardinalityMismatches,
            long correctionMismatches,
            long doubleReversalTargets) {
        public long total() {
            return postingsWithoutEntries + normalEntryMismatches
                    + normalPostingCardinalityMismatches + correctionMismatches
                    + doubleReversalTargets;
        }
    }
}
