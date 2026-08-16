package com.aicostops.cost.review.domain;

/**
 * Lifecycle of a duplicate candidate. All non-OPEN states are terminal; a
 * terminal pair is never reopened by a rescan of the same algorithm version.
 */
public enum CandidateStatus {
    OPEN,
    KEPT_CLEAN,
    CONFIRMED_DUPLICATE,
    SUPERSEDED
}
