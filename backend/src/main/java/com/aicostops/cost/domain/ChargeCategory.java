package com.aicostops.cost.domain;

/** Coarse category of a persisted {@code charge_fact}. */
public enum ChargeCategory {
    USAGE,
    /** UAT regression fixture category (persisted by the UAT regression suite). */
    UAT_REGRESSION
}
