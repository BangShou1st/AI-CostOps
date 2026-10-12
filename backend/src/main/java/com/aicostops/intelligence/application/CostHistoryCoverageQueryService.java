package com.aicostops.intelligence.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.domain.CostHistoryCoverage;
import com.aicostops.intelligence.domain.CostHistoryCoverage.*;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper;
import com.aicostops.intelligence.infrastructure.CostHistoryCoverageMapper.CoverageRow;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read one bounded scope/window without inventing zero amounts for unknown days. */
@Service
public class CostHistoryCoverageQueryService {
    private final AuthorizationContextService contexts;
    private final CostHistoryCoverageMapper store;
    private final CostFactsMapper facts;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public CostHistoryCoverageQueryService(AuthorizationContextService contexts,
            CostHistoryCoverageMapper store, CostFactsMapper facts, Clock clock) {
        this.contexts = contexts;
        this.store = store;
        this.facts = facts;
        this.clock = clock;
    }

    public record DayView(LocalDate postingDay, String observation, String amount,
            boolean reviewValid, CoverageRow review) {}
    public record CoverageView(long revision, String currency, Scope scope, long scopeId,
            String dateBasis, List<DayView> days) {}

    @Transactional(readOnly = true)
    public List<CostHistoryCoverageMapper.ScopeOption> scopes(AuthenticatedUser user, String search) {
        var context = contexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        if (search == null || search.length() > 100) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid scope search", "Search names with at most 100 characters.");
        }
        return store.scopes(context.organizationId(), search.strip());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CoverageView coverage(AuthenticatedUser user, String currency, Scope scope,
            long scopeId, LocalDate start, LocalDate end) {
        var context = contexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        currency = CostIntelligenceValidation.validatedCurrency(currency);
        if (scope == null || scopeId <= 0 || start == null || end == null || !start.isBefore(end)
                || ChronoUnit.DAYS.between(start, end) > 31
                || end.isAfter(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid coverage window", "Use 1 to 31 past UTC posting days; end is exclusive.");
        }
        var org = context.organizationId();
        if (store.ownedScope(org, scope.name(), scopeId) != 1) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Scope not found", "The coverage scope does not belong to this organization.");
        }
        var revision = store.revision(org, currency);
        var reviews = store.listScope(org, currency, scope.name(), scopeId, start, end).stream()
                .collect(Collectors.toMap(CoverageRow::postingDay, row -> row));
        var amounts = dailyAmounts(org, currency, scope, scopeId, start, end);
        var days = new ArrayList<DayView>();
        for (var day = start; day.isBefore(end); day = day.plusDays(1)) {
            var row = reviews.get(day);
            var valid = false;
            if (row != null) {
                var review = new Review(scope, scopeId, day, Status.valueOf(row.status()),
                        new LedgerStamp(row.ledgerEntryCount(), row.ledgerMaxEntryId()));
                valid = review.provesComplete(scope, scopeId, day,
                        store.ledgerStamp(org, currency, scope.name(), scopeId, day, day.plusDays(1)));
            }
            var recorded = amounts.containsKey(day);
            var observation = recorded ? "OBSERVED" : valid ? "REVIEWED_ZERO" : "UNKNOWN";
            var amount = recorded ? amounts.get(day).toPlainString() : valid ? "0" : null;
            days.add(new DayView(day, observation, amount, valid, row));
        }
        return new CoverageView(revision, currency, scope, scopeId, "UTC_POSTING_DAY", List.copyOf(days));
    }

    private Map<LocalDate, BigDecimal> dailyAmounts(long org, String currency, Scope scope,
            long scopeId, LocalDate start, LocalDate end) {
        if (scope == Scope.ORGANIZATION) {
            return facts.orgDaily(org, currency, start, end).stream()
                    .collect(Collectors.toMap(CostFactsMapper.DailyTotal::day, CostFactsMapper.DailyTotal::amount));
        }
        var rows = switch (scope) {
            case PROJECT -> facts.projectDaily(org, currency, start, end);
            case TEAM -> facts.teamDaily(org, currency, start, end);
            case COST_CENTER -> facts.costCenterDaily(org, currency, start, end);
            default -> throw new IllegalStateException("Unsupported coverage scope");
        };
        return rows.stream().filter(row -> row.scopeId() == scopeId)
                .collect(Collectors.toMap(CostFactsMapper.ScopedDailyTotal::day, CostFactsMapper.ScopedDailyTotal::amount));
    }
}
