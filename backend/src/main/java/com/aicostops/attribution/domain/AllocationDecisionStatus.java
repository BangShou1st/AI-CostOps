package com.aicostops.attribution.domain;

/**
 * Schema-level lifecycle of a decision. This foundation only persists DRAFT
 * rows; the DRAFT -> CONFIRMED / SUPERSEDED transitions belong to the Group 3
 * allocation confirm workflow (#49).
 */
public enum AllocationDecisionStatus {
    DRAFT,
    CONFIRMED,
    SUPERSEDED
}
