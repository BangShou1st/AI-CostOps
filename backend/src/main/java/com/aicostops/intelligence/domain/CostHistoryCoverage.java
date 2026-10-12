package com.aicostops.intelligence.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Explicit review evidence for one UTC posting day and one exact financial scope. */
public final class CostHistoryCoverage {
    private CostHistoryCoverage() {}

    public enum Scope { ORGANIZATION, PROJECT, TEAM, COST_CENTER }
    public enum Status { REVIEWED_COMPLETE, REVOKED }

    /** Append-only ledger identity; a changed count or maximum invalidates the review. */
    public record LedgerStamp(long entryCount, long maximumEntryId) {
        public LedgerStamp {
            if (entryCount < 0 || maximumEntryId < 0
                    || (entryCount == 0) != (maximumEntryId == 0)) {
                throw new IllegalArgumentException("Invalid ledger coverage stamp");
            }
        }
    }

    public record Review(Scope scope, long scopeId, LocalDate day, Status status, LedgerStamp ledger) {
        public Review {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(day);
            Objects.requireNonNull(status);
            Objects.requireNonNull(ledger);
            if (scopeId <= 0) throw new IllegalArgumentException("A positive scope ID is required");
        }

        public boolean provesComplete(Scope requestedScope, long requestedId,
                LocalDate requestedDay, LedgerStamp currentLedger) {
            return scope == requestedScope && scopeId == requestedId && day.equals(requestedDay)
                    && status == Status.REVIEWED_COMPLETE && ledger.equals(currentLedger);
        }
    }

    /** A known ledger value stays observed even when no completeness declaration exists. */
    public static boolean isObserved(boolean hasLedgerRecord, Review review, Scope scope,
            long scopeId, LocalDate day, LedgerStamp currentLedger) {
        return hasLedgerRecord || (review != null
                && review.provesComplete(scope, scopeId, day, currentLedger));
    }
}
