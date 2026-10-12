package com.aicostops.intelligence.domain;

import static org.junit.jupiter.api.Assertions.*;
import static com.aicostops.intelligence.domain.CostHistoryCoverage.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CostHistoryCoverageTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);
    private static final LedgerStamp EMPTY = new LedgerStamp(0, 0);

    @Test void reviewedEmptyDayIsObservedButUnknownDayIsNot() {
        var review = new Review(Scope.PROJECT, 7, DAY, Status.REVIEWED_COMPLETE, EMPTY);
        assertTrue(isObserved(false, review, Scope.PROJECT, 7, DAY, EMPTY));
        assertFalse(isObserved(false, null, Scope.PROJECT, 7, DAY, EMPTY));
    }

    @Test void coverageCannotBeInheritedAcrossScopeOrDay() {
        var review = new Review(Scope.ORGANIZATION, 7, DAY, Status.REVIEWED_COMPLETE, EMPTY);
        assertFalse(isObserved(false, review, Scope.PROJECT, 7, DAY, EMPTY));
        assertFalse(isObserved(false, review, Scope.ORGANIZATION, 8, DAY, EMPTY));
        assertFalse(isObserved(false, review, Scope.ORGANIZATION, 7, DAY.plusDays(1), EMPTY));
    }

    @Test void revocationOrLateLedgerArrivalInvalidatesCompleteness() {
        var review = new Review(Scope.TEAM, 7, DAY, Status.REVIEWED_COMPLETE, EMPTY);
        assertFalse(review.provesComplete(Scope.TEAM, 7, DAY, new LedgerStamp(1, 15)));
        var revoked = new Review(Scope.TEAM, 7, DAY, Status.REVOKED, EMPTY);
        assertFalse(isObserved(false, revoked, Scope.TEAM, 7, DAY, EMPTY));
        var existing = new Review(Scope.TEAM, 7, DAY, Status.REVIEWED_COMPLETE, new LedgerStamp(1, 15));
        assertFalse(existing.provesComplete(Scope.TEAM, 7, DAY, new LedgerStamp(2, 16)));
    }

    @Test void actualZeroLedgerRecordRemainsAnObservationWithoutCoverage() {
        assertTrue(isObserved(true, null, Scope.COST_CENTER, 7, DAY, new LedgerStamp(2, 16)));
        var revoked = new Review(Scope.COST_CENTER, 7, DAY, Status.REVOKED, EMPTY);
        assertTrue(isObserved(true, revoked, Scope.COST_CENTER, 7, DAY, new LedgerStamp(2, 16)));
    }

    @Test void invalidStampsAndScopesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LedgerStamp(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LedgerStamp(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LedgerStamp(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Review(Scope.TEAM, 0, DAY, Status.REVOKED, EMPTY));
    }
}
