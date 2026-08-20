package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.application.ReconciliationReadModels.MatchRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class ReconciliationTruthHasher {

    public String hash(List<MatchRow> rows) {
        var canonical = new StringBuilder(ReconciliationAlgorithm.VERSION).append('\n');
        for (var row : rows) {
            canonical.append(row.providerAccountId()).append('|')
                    .append(row.currency()).append('|')
                    .append(row.externalPresent() ? '1' : '0').append('|')
                    .append(row.externalRowCount()).append('|')
                    .append(row.externalAmount() == null ? "-" : ReconciliationMoney.format(row.externalAmount()))
                    .append('|')
                    .append(row.internalPresent() ? '1' : '0').append('|')
                    .append(row.internalRowCount()).append('|')
                    .append(row.internalAmount() == null ? "-" : ReconciliationMoney.format(row.internalAmount()))
                    .append('\n');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }
}
