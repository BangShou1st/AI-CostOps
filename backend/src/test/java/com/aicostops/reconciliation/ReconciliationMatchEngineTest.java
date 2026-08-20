package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.cost.application.ReconciliationExternalTruthPort.ExternalAggregate;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort.InternalAggregate;
import com.aicostops.reconciliation.application.ReconciliationMatchEngine;
import com.aicostops.reconciliation.application.ReconciliationTruthHasher;
import com.aicostops.reconciliation.domain.ReconciliationCaseType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationMatchEngineTest {

    private final ReconciliationMatchEngine engine = new ReconciliationMatchEngine();
    private final ReconciliationTruthHasher hasher = new ReconciliationTruthHasher();

    @Test
    void exactMatchHasNoCase() {
        var summary = engine.match(
                List.of(external(7, "USD", 2, "10.00000000")),
                List.of(internal(7, "USD", 3, "10.00000000")),
                money("0.00000000"));

        assertThat(summary.matchedCount()).isEqualTo(1);
        assertThat(summary.discrepancyCount()).isZero();
        assertThat(summary.rows()).singleElement().satisfies(row -> {
            assertThat(row.caseType()).isNull();
            assertThat(row.externalPresent()).isTrue();
            assertThat(row.internalPresent()).isTrue();
            assertThat(row.difference()).isEqualByComparingTo("0.00000000");
        });
    }

    @Test
    void differenceIsInternalMinusExternal() {
        var row = engine.match(
                List.of(external(7, "USD", 1, "10.00000000")),
                List.of(internal(7, "USD", 1, "12.00000000")),
                money("0.00000000")).rows().getFirst();

        assertThat(row.difference()).isEqualByComparingTo("2.00000000");
        assertThat(row.caseType()).isEqualTo(ReconciliationCaseType.AMOUNT_MISMATCH);
    }

    @Test
    void missingInternalAndExternalUsePresenceNotZeroAmount() {
        var rows = engine.match(
                List.of(external(1, "CNY", 2, "0.00000000")),
                List.of(internal(2, "USD", 1, "0.00000000")),
                money("0.00000000")).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).caseType()).isEqualTo(ReconciliationCaseType.MISSING_INTERNAL);
        assertThat(rows.get(0).externalPresent()).isTrue();
        assertThat(rows.get(1).caseType()).isEqualTo(ReconciliationCaseType.MISSING_EXTERNAL);
        assertThat(rows.get(1).internalPresent()).isTrue();
    }

    @Test
    void configuredToleranceMatchesSmallDifference() {
        var row = engine.match(
                List.of(external(7, "USD", 1, "10.00000000")),
                List.of(internal(7, "USD", 1, "10.00000001")),
                money("0.00000001")).rows().getFirst();

        assertThat(row.caseType()).isNull();
    }

    @Test
    void orderingAndHashAreDeterministic() {
        var first = engine.match(
                List.of(external(9, "USD", 1, "2.00000000"),
                        external(7, "CNY", 1, "1.00000000")),
                List.of(internal(7, "CNY", 1, "1.00000000"),
                        internal(9, "USD", 1, "2.00000000")),
                money("0.00000000"));
        var second = engine.match(
                List.of(external(7, "CNY", 1, "1.00000000"),
                        external(9, "USD", 1, "2.00000000")),
                List.of(internal(9, "USD", 1, "2.00000000"),
                        internal(7, "CNY", 1, "1.00000000")),
                money("0.00000000"));

        assertThat(first.rows()).extracting(r -> r.providerAccountId() + ":" + r.currency())
                .containsExactly("7:CNY", "9:USD");
        assertThat(hasher.hash(first.rows())).isEqualTo(hasher.hash(second.rows()));
        assertThat(hasher.hash(first.rows())).hasSize(64);
    }

    @Test
    void rejectsNonExactScaleOrNegativeTolerance() {
        assertThatThrownBy(() -> engine.match(List.of(), List.of(), money("-0.00000001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.match(
                List.of(new ExternalAggregate(1, "USD", 1, new BigDecimal("1.000000001"))),
                List.of(), money("0.00000000")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExternalAggregate external(long providerId, String currency, long rows, String amount) {
        return new ExternalAggregate(providerId, currency, rows, money(amount));
    }

    private static InternalAggregate internal(long providerId, String currency, long rows, String amount) {
        return new InternalAggregate(providerId, currency, rows, money(amount));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
