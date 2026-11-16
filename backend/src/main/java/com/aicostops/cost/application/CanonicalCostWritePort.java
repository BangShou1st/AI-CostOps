package com.aicostops.cost.application;

import java.util.List;

/** Outbound port for persisting canonical facts inside the caller's transaction. */
public interface CanonicalCostWritePort {

    CanonicalWriteResult write(CanonicalizationInput input);

    /**
     * Persists several raw records' canonical facts in one caller-owned
     * transaction. Implementations may batch the underlying statements; the
     * default preserves the original one-by-one semantics for test doubles.
     */
    default CanonicalWriteResult writeAll(List<CanonicalizationInput> inputs) {
        var total = CanonicalWriteResult.empty();
        for (var input : inputs) {
            total = total.plus(write(input));
        }
        return total;
    }
}
