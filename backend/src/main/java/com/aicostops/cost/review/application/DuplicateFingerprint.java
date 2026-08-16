package com.aicostops.cost.review.application;

import com.aicostops.cost.review.application.DuplicateReviewReadModels.ChargeFactLineageRow;
import com.aicostops.cost.review.domain.CandidateType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic evidence signature and candidate fingerprint of the V1 duplicate
 * detection algorithm. The signature covers exactly the evidence dimensions the
 * schema persists (org, provider account via lineage, provider, category,
 * currency, half-open period window, amount); the pair fingerprint is an
 * explainable heuristic marker, never a row identity.
 */
public final class DuplicateFingerprint {

    public static final String ALGORITHM_VERSION = "v1";

    public static final String MATCH_REASON_EXACT =
            "exact evidence match: same provider account/category/currency, identical period, equal amount";
    public static final String MATCH_REASON_OVERLAP =
            "overlap evidence match: same provider account/category/currency, overlapping periods";

    private DuplicateFingerprint() {
    }

    /** 64-char lowercase hex SHA-256 of the canonical single-charge evidence. */
    public static String evidenceSignature(ChargeFactLineageRow charge) {
        var canonical = "org=" + charge.organizationId()
                + "\naccount=" + charge.providerAccountId()
                + "\nprovider=" + charge.providerCode()
                + "\ncategory=" + charge.chargeCategory()
                + "\ncurrency=" + charge.currency()
                + "\nstart=" + (charge.periodStart() == null ? "" : charge.periodStart())
                + "\nend=" + (charge.periodEnd() == null ? "" : charge.periodEnd())
                + "\namount=" + charge.amount().toPlainString();
        return sha256Hex(canonical);
    }

    /** Order-independent fingerprint of one normalized candidate pair. */
    public static String pairFingerprint(CandidateType type, String leftSignature, String rightSignature) {
        var low = leftSignature.compareTo(rightSignature) <= 0 ? leftSignature : rightSignature;
        var high = leftSignature.compareTo(rightSignature) <= 0 ? rightSignature : leftSignature;
        var canonical = "algorithmVersion=" + ALGORITHM_VERSION
                + "\ncandidateType=" + type.name()
                + "\nleftSignature=" + low
                + "\nrightSignature=" + high;
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
