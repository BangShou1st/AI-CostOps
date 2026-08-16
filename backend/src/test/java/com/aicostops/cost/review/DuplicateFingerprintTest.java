package com.aicostops.cost.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.review.application.DuplicateFingerprint;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactLineageRow;
import com.aicostops.cost.review.domain.CandidateType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DuplicateFingerprintTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void sameEvidenceProducesSameFingerprint() {
        var left = charge(1L, "10.00000000");
        var right = charge(2L, "10.00000000");

        var first = DuplicateFingerprint.pairFingerprint(CandidateType.EXACT,
                DuplicateFingerprint.evidenceSignature(left), DuplicateFingerprint.evidenceSignature(right));
        var second = DuplicateFingerprint.pairFingerprint(CandidateType.EXACT,
                DuplicateFingerprint.evidenceSignature(charge(1L, "10.00000000")),
                DuplicateFingerprint.evidenceSignature(charge(2L, "10.00000000")));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void pairFingerprintIsOrderIndependent() {
        var low = DuplicateFingerprint.evidenceSignature(charge(1L, "10.00000000"));
        var high = DuplicateFingerprint.evidenceSignature(charge(2L, "20.00000000"));

        assertThat(DuplicateFingerprint.pairFingerprint(CandidateType.EXACT, low, high))
                .isEqualTo(DuplicateFingerprint.pairFingerprint(CandidateType.EXACT, high, low));
    }

    @Test
    void differingAmountCurrencyOrWindowChangesTheFingerprint() {
        var base = DuplicateFingerprint.evidenceSignature(charge(1L, "10.00000000"));
        var other = DuplicateFingerprint.evidenceSignature(charge(2L, "20.00000000"));

        var amountChanged = DuplicateFingerprint.evidenceSignature(
                new ChargeFactLineageRow(1L, 1L, 5L, "GLM", "USAGE",
                        new BigDecimal("11.00000000"), "CNY", START, END, ReviewStatus.CLEAN));
        var currencyChanged = DuplicateFingerprint.evidenceSignature(charge(1L, "10.00000000", "USD"));
        var windowChanged = DuplicateFingerprint.evidenceSignature(
                new ChargeFactLineageRow(1L, 1L, 5L, "GLM", "USAGE",
                        new BigDecimal("10.00000000"), "CNY", START, END.plusSeconds(1), ReviewStatus.CLEAN));

        assertThat(amountChanged).isNotEqualTo(base);
        assertThat(currencyChanged).isNotEqualTo(base);
        assertThat(windowChanged).isNotEqualTo(base);

        var typeExacts = DuplicateFingerprint.pairFingerprint(CandidateType.EXACT, base, other);
        assertThat(DuplicateFingerprint.pairFingerprint(CandidateType.OVERLAP, base, other))
                .isNotEqualTo(typeExacts);
    }

    @Test
    void fingerprintIsSixtyFourLowercaseHexCharacters() {
        var fingerprint = DuplicateFingerprint.pairFingerprint(CandidateType.EXACT,
                DuplicateFingerprint.evidenceSignature(charge(1L, "10.00000000")),
                DuplicateFingerprint.evidenceSignature(charge(2L, "10.00000000")));

        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    private static ChargeFactLineageRow charge(long id, String amount) {
        return charge(id, amount, "CNY");
    }

    private static ChargeFactLineageRow charge(long id, String amount, String currency) {
        return new ChargeFactLineageRow(id, 1L, 5L, "GLM", "USAGE",
                new BigDecimal(amount), currency, START, END, ReviewStatus.CLEAN);
    }
}
