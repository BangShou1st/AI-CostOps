package com.aicostops.intelligence.application;

import com.aicostops.intelligence.domain.CostAnalysisInputFingerprint;
import com.aicostops.intelligence.domain.CostHistoryCoverage.*;
import com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Captures review evidence and append-only ledger identity in the analysis transaction. */
@Service
public class CostAnalysisInputService {
    public static final int HISTORY_DAYS = 29;
    private final CostHistoryCoverageMapper coverage;

    public CostAnalysisInputService(CostHistoryCoverageMapper coverage) { this.coverage = coverage; }

    public record ScopeKey(Scope scope, long id) {}
    public record Input(String fingerprint, Map<ScopeKey, Set<LocalDate>> reviewedDays) {
        public Input {
            var immutable = new HashMap<ScopeKey, Set<LocalDate>>();
            reviewedDays.forEach((key, days) -> immutable.put(key, Set.copyOf(days)));
            reviewedDays = Map.copyOf(immutable);
        }
        public Set<LocalDate> days(Scope scope, long id) {
            return reviewedDays.getOrDefault(new ScopeKey(scope, id), Set.of());
        }
    }

    public String currentFingerprint(long org, String currency, LocalDate date, int version) {
        return fingerprint(org, currency, date, version, coverage.revision(org, currency));
    }

    private String fingerprint(long org, String currency, LocalDate date, int version, long revision) {
        var ledger = coverage.ledgerStamp(org, currency, Scope.ORGANIZATION.name(), org,
                date.minusDays(HISTORY_DAYS), date);
        return CostAnalysisInputFingerprint.of(org, currency, date, version, revision, ledger);
    }

    public Input lockAndCapture(long org, String currency, LocalDate date, int version) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Analysis inputs must be captured in a transaction");
        }
        coverage.ensureRevision(org, currency);
        var revision = coverage.lockRevision(org, currency);
        var fingerprint = fingerprint(org, currency, date, version, revision);
        // An enclosing transaction may already have an older repeatable-read view.
        // Refuse to pair that view's reviews with a newer revision obtained by a locking read.
        if (coverage.revision(org, currency) != revision) {
            throw new DomainException(HttpStatus.CONFLICT,
                    ProblemCode.STATE_CONFLICT, "Coverage input changed",
                    "Restart the analysis transaction to capture the current coverage revision.");
        }
        var days = new HashMap<ScopeKey, Set<LocalDate>>();
        for (var row : coverage.validDays(org, currency, date.minusDays(HISTORY_DAYS), date)) {
            var scope = Scope.valueOf(row.scopeType());
            days.computeIfAbsent(new ScopeKey(scope, row.scopeId()), key -> new HashSet<>()).add(row.postingDay());
        }
        return new Input(fingerprint, days);
    }
}
