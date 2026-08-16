package com.aicostops.attribution.domain;

/** Where a decision came from; a RULE decision must trace its rule version. */
public enum AllocationDecisionSource {
    MANUAL,
    RULE
}
