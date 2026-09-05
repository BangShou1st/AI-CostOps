package com.aicostops.ledger.application;

/**
 * Consumer-owned Ledger seam evaluated inside the Provider Charge posting
 * transaction after the BillingPeriod lock and before the Charge is posted.
 * The Ledger application never imports reconciliation types: the bounded
 * decision is supplied by an infrastructure adapter.
 */
public interface ProviderChargeHybridPostingGuard {

    HybridPostingDecision checkHybridPostingEligibility(
            long organizationId, long chargeFactId, long billingPeriodId, String currency);

    enum HybridPostingOutcome {
        /** No Hybrid overlap and no conflicting disposition: normal V1 posting. */
        ALLOWED,
        /** Explicit DIRECT_PROVIDER_CHARGE disposition exists: normal V1 posting. */
        ALLOWED_DIRECT_DISPOSITION,
        /** Hybrid overlap without a DIRECT disposition: posting is rejected. */
        BLOCKED_HYBRID_OVERLAP,
        /** RECONCILIATION_EVIDENCE disposition: permanently non-postable. */
        BLOCKED_RECONCILIATION_EVIDENCE
    }

    record HybridPostingDecision(HybridPostingOutcome outcome) {

        public boolean allowed() {
            return outcome == HybridPostingOutcome.ALLOWED
                    || outcome == HybridPostingOutcome.ALLOWED_DIRECT_DISPOSITION;
        }
    }
}
