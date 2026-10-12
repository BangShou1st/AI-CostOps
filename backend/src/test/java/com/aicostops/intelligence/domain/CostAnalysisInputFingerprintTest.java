package com.aicostops.intelligence.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CostAnalysisInputFingerprintTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);
    private static final CostHistoryCoverage.LedgerStamp EMPTY = new CostHistoryCoverage.LedgerStamp(0, 0);

    @Test void repeatedInputsConvergeToOneIdentity() {
        var hash = CostAnalysisInputFingerprint.of(1, "CNY", DAY, 3, 0, EMPTY);
        assertEquals(64, hash.length());
        assertEquals(hash, CostAnalysisInputFingerprint.of(1, "CNY", DAY, 3, 0, EMPTY));
    }

    @Test void reviewRevocationOrLatePostingRequiresAnotherSnapshot() {
        var original = CostAnalysisInputFingerprint.of(1, "CNY", DAY, 3, 0, EMPTY);
        assertNotEquals(original, CostAnalysisInputFingerprint.of(1, "CNY", DAY, 3, 1, EMPTY));
        assertNotEquals(original, CostAnalysisInputFingerprint.of(1, "CNY", DAY, 3, 0,
                new CostHistoryCoverage.LedgerStamp(1, 9)));
    }

    @Test void identitiesNeverCrossOrganizationCurrencyDateOrAlgorithm() {
        var original = CostAnalysisInputFingerprint.of(1, "CNY", DAY, 3, 0, EMPTY);
        assertNotEquals(original, CostAnalysisInputFingerprint.of(2, "CNY", DAY, 3, 0, EMPTY));
        assertNotEquals(original, CostAnalysisInputFingerprint.of(1, "USD", DAY, 3, 0, EMPTY));
        assertNotEquals(original, CostAnalysisInputFingerprint.of(1, "CNY", DAY.plusDays(1), 3, 0, EMPTY));
        assertNotEquals(original, CostAnalysisInputFingerprint.of(1, "CNY", DAY, 4, 0, EMPTY));
    }
}
