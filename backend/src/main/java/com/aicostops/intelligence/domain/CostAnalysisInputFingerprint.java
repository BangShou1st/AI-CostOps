package com.aicostops.intelligence.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable identity of an algorithm, review revision and append-only ledger window. */
public final class CostAnalysisInputFingerprint {
    private CostAnalysisInputFingerprint() {}

    public static String of(long organizationId, String currency, LocalDate analysisDate,
            int algorithmVersion, long coverageRevision, CostHistoryCoverage.LedgerStamp ledger) {
        Objects.requireNonNull(currency);
        Objects.requireNonNull(analysisDate);
        Objects.requireNonNull(ledger);
        if (organizationId <= 0 || algorithmVersion <= 0 || coverageRevision < 0
                || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid analysis input identity");
        }
        var canonical = String.join("|", "UTC_POSTING_29_DAYS", Long.toString(organizationId), currency,
                analysisDate.toString(), Integer.toString(algorithmVersion), Long.toString(coverageRevision),
                Long.toString(ledger.entryCount()), Long.toString(ledger.maximumEntryId()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
