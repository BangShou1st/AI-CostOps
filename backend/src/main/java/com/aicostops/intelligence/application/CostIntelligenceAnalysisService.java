package com.aicostops.intelligence.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.domain.CostAnomalyEngine;
import com.aicostops.intelligence.domain.CostAnomalyEngine.Contribution;
import com.aicostops.intelligence.domain.CostAnomalyEngine.DailyBucket;
import com.aicostops.intelligence.domain.ForecastEngine;
import com.aicostops.intelligence.domain.CostHistoryCoverage.Scope;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import com.aicostops.ingestion.application.SecretShapes;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic Cost Intelligence analysis orchestration (M18 V3).
 *
 * <p>Owns the analysis entry points, the due-batch sweep and the anomaly/forecast time-series
 * flow. Results are DERIVED evidence, never Ledger truth; all money comes from settled Gateway
 * truth through {@link CostFactsMapper}. Currencies never mix. The run transaction itself belongs
 * to {@link CostIntelligenceRunService}: this service only supplies the analysis callback, so
 * derived results and COMPLETED commit together and any failure rolls everything back.
 */
@Service
public class CostIntelligenceAnalysisService {

    private static final int HISTORY_DAYS = CostAnalysisInputService.HISTORY_DAYS;

    /** Deterministic keyset page size for the scheduled organization sweep. */
    private static final int SCHEDULER_ORG_PAGE_SIZE = 100;

    /**
     * Bound on total page-fetch queries for one cursor before the round gives
     * up: 1 initial query + 3 retries of the same page. Keeps a persistently
     * unavailable datasource from turning the scheduler thread into a tight
     * retry loop.
     */
    private static final int MAX_PAGE_FETCH_ATTEMPTS = 4;
    private static final Logger LOG =
            LoggerFactory.getLogger(CostIntelligenceAnalysisService.class);

    private static final Map<String, BigDecimal> MATERIALITY = Map.of(
            "USD", new BigDecimal("5.00"), "EUR", new BigDecimal("5.00"),
            "CNY", new BigDecimal("30.00"), "GBP", new BigDecimal("4.00"));

    private final AuthorizationContextService authorizationContexts;
    private final CostFactsMapper facts;
    private final IntelligenceMapper store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CostIntelligenceRunService runs;
    private final SavingsRecommendationGenerator savings;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public CostIntelligenceAnalysisService(
            AuthorizationContextService authorizationContexts,
            CostFactsMapper facts,
            IntelligenceMapper store,
            ObjectMapper objectMapper,
            Clock clock,
            CostIntelligenceRunService runs,
            SavingsRecommendationGenerator savings) {
        this.authorizationContexts = authorizationContexts;
        this.facts = facts;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.runs = runs;
        this.savings = savings;
    }

    /** Runs (or converges on) one deterministic analysis. Scheduler + API share this. */
    public AnalysisRunOutcome runAnalysis(long organizationId, LocalDate analysisDate, String currency) {
        return runs.run(organizationId, analysisDate, currency, CostIntelligenceValidation.RUN_VERSION,
                (runId, input) -> analyze(organizationId, runId, analysisDate, currency, clock.instant(), input));
    }

    public AnalysisRunOutcome runAnalysis(AuthenticatedUser user, LocalDate analysisDate, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        return runAnalysis(context.organizationId(), validatedDate(analysisDate),
                CostIntelligenceValidation.validatedCurrency(currency));
    }

    /** Scheduler entry: recently active orgs x settled currencies for yesterday. */
    public void runDueAnalyses() {
        // The round's date and activity window are fixed once so every page walks the same
        // deterministic org_id ordering. Pages advance by keyset (org_id > last saw) until a
        // short page proves the end of the round, so an org is never skipped or revisited no
        // matter how many organizations are active.
        //
        // Every query that could fail is confined so one organization (or one page fetch)
        // can never stop the round: runAnalysis failures are caught per org/currency and
        // the per-org settledCurrencies lookup is caught per organization. A page-fetch
        // failure keeps the cursor unchanged and retries the SAME page in place (at most
        // 1 initial query + 3 retries): the cursor may only move after a page was fetched
        // and fully processed, so no organization is ever skipped or revisited. If the
        // page keeps failing the round is abandoned with WARN — the next scheduled round
        // naturally retries — instead of looping or rescanning earlier pages.
        var yesterday = LocalDate.now(clock).minusDays(1);
        var since = yesterday.minusDays(30);
        long afterOrgId = 0L;
        int pageFetchFailures = 0;
        while (true) {
            List<Long> page;
            try {
                page = facts.recentlyActiveOrgs(since, afterOrgId, SCHEDULER_ORG_PAGE_SIZE);
            } catch (RuntimeException failure) {
                LOG.warn("Cost Intelligence scheduled organization page fetch failed after "
                                + "organization {} (attempt {} of {}): {}", afterOrgId,
                        pageFetchFailures + 1, MAX_PAGE_FETCH_ATTEMPTS,
                        SecretShapes.redact(failure.toString()));
                // The cursor stays where it was: the retry re-reads the same page,
                // so a transient failure can neither skip unprocessed organizations
                // nor rewind to already-processed ones.
                if (++pageFetchFailures >= MAX_PAGE_FETCH_ATTEMPTS) {
                    LOG.warn("Cost Intelligence scheduled round abandoned after {} "
                                    + "consecutive page fetch failures at organization {}",
                            pageFetchFailures, afterOrgId);
                    return;
                }
                continue;
            }
            pageFetchFailures = 0;
            for (var orgId : page) {
                List<String> currencies;
                try {
                    currencies = facts.settledCurrencies(orgId);
                } catch (RuntimeException failure) {
                    LOG.warn("Cost Intelligence scheduled currency lookup failed for "
                                    + "organization {}: {}", orgId,
                            SecretShapes.redact(failure.toString()));
                    continue;
                }
                for (var currency : currencies) {
                    try {
                        runAnalysis(orgId, yesterday, currency);
                    } catch (RuntimeException failure) {
                        // One org/currency failure never blocks the remaining runs, including the
                        // rest of the current page and every later page. Runs that already
                        // captured an input identity leave a FAILED run row; failures before
                        // identity capture (e.g. during coverage revision acquisition) leave no
                        // run row, so this log is the only trace those failures ever had. The
                        // failure text passes the shared fail-closed secret redactor: the
                        // scheduler must never write credential-shaped substrings into logs.
                        LOG.warn("Cost Intelligence scheduled analysis failed for organization {} "
                                + "currency {} date {}: {}", orgId, currency, yesterday,
                                SecretShapes.redact(failure.toString()));
                    }
                }
            }
            if (page.size() < SCHEDULER_ORG_PAGE_SIZE) {
                return;
            }
            afterOrgId = page.getLast();
        }
    }

    private void analyze(long organizationId, long runId, LocalDate analysisDate, String currency, Instant now,
            CostAnalysisInputService.Input input) {
        var end = analysisDate;
        var start = end.minusDays(HISTORY_DAYS);
        var materiality = MATERIALITY.getOrDefault(currency, new BigDecimal("5.00"));
        var orgSeries = completeSeries(facts.orgDaily(organizationId, currency, start, end).stream()
                .collect(LinkedHashMap<LocalDate, BigDecimal>::new,
                        (map, row) -> map.put(row.day(), row.amount()), Map::putAll),
                start, end, currency);
        var orgAnomalies = CostAnomalyEngine.detect("ORGANIZATION", "org:" + organizationId,
                orgSeries, childContributions(organizationId, currency, start, end, materiality),
                materiality);
        persistAnomalies(organizationId, runId, orgAnomalies, now);
        for (var grain : projectGrains(organizationId, currency, start, end)) {
            persistAnomalies(organizationId, runId,
                    CostAnomalyEngine.detect("PROJECT", grain.key(), grain.series(),
                            List.of(), materiality),
                    now);
        }
        // TEAM/COST_CENTER grains feed scoped forecasts below; anomaly persistence stays on the
        // frozen grain set because cost_anomaly.chk_anomaly_grain only admits
        // ORGANIZATION/PROJECT/PROVIDER/LOGICAL_MODEL.
        for (var grain : providerGrains(organizationId, currency, start, end)) {
            persistAnomalies(organizationId, runId,
                    CostAnomalyEngine.detect("PROVIDER", grain.key(), grain.series(),
                            List.of(), materiality),
                    now);
        }
        for (var grain : modelGrains(organizationId, currency, start, end)) {
            persistAnomalies(organizationId, runId,
                    CostAnomalyEngine.detect("LOGICAL_MODEL", grain.key(), grain.series(),
                            List.of(), materiality),
                    now);
        }
        var observedThrough = end.minusDays(1);
        var remaining = remainingDays(analysisDate);
        persistForecast(organizationId, runId, "ORGANIZATION", "org:" + organizationId,
                orgSeries, remaining, observedThrough, currency, now, input);
        for (var grain : includeReviewedGrains(projectGrains(organizationId, currency, start, end),
                input, Scope.PROJECT, start, end, currency)) {
            persistForecast(organizationId, runId, "PROJECT", grain.key(),
                    grain.series(), remaining, observedThrough, currency, now, input);
        }
        // P1: TEAM / COST_CENTER scoped forecast grains (never fall back to whole-org).
        for (var grain : includeReviewedGrains(teamGrains(organizationId, currency, start, end),
                input, Scope.TEAM, start, end, currency)) {
            persistForecast(organizationId, runId, "TEAM", grain.key(),
                    grain.series(), remaining, observedThrough, currency, now, input);
        }
        for (var grain : includeReviewedGrains(costCenterGrains(organizationId, currency, start, end),
                input, Scope.COST_CENTER, start, end, currency)) {
            persistForecast(organizationId, runId, "COST_CENTER", grain.key(),
                    grain.series(), remaining, observedThrough, currency, now, input);
        }
        savings.generate(organizationId, runId, start, end, currency, materiality, now);
    }

    private List<Contribution> childContributions(long organizationId, String currency,
            LocalDate start, LocalDate end, BigDecimal materiality) {
        var contributions = new ArrayList<Contribution>();
        for (var grain : providerGrains(organizationId, currency, start, end)) {
            var delta = latestDelta(grain.series());
            if (delta != null && delta.abs().compareTo(materiality) >= 0) {
                contributions.add(new Contribution("PROVIDER",
                        grain.label() == null ? ("provider:" + grain.scopeId()) : grain.label(), delta));
            }
        }
        for (var grain : modelGrains(organizationId, currency, start, end)) {
            var delta = latestDelta(grain.series());
            if (delta != null && delta.abs().compareTo(materiality) >= 0) {
                contributions.add(new Contribution("LOGICAL_MODEL", grain.key(), delta));
            }
        }
        contributions.sort(Comparator.comparing(Contribution::deltaAmount).reversed());
        return contributions.size() <= 5 ? contributions : contributions.subList(0, 5);
    }

    private BigDecimal latestDelta(List<DailyBucket> series) {
        if (series.size() < CostAnomalyEngine.MIN_HISTORY_BUCKETS + 1) return null;
        var history = series.subList(0, series.size() - 1).stream()
                .map(DailyBucket::amount).sorted().toList();
        var median = history.get(history.size() / 2);
        return series.get(series.size() - 1).amount().subtract(median);
    }

    private void persistAnomalies(long organizationId, long runId,
            List<CostAnomalyEngine.Anomaly> anomalies, Instant now) {
        for (var anomaly : anomalies) {
            store.insertAnomaly(organizationId, runId, anomaly.grainType(), anomaly.grainKey(),
                    anomaly.currency(), anomaly.observedAmount(), anomaly.baselineAmount(),
                    anomaly.deltaAmount(), anomaly.deltaPercent(), anomaly.robustZScore(),
                    driversJson(anomaly.drivers()), now);
        }
    }

    private void persistForecast(long organizationId, long runId, String scopeType, String scopeKey,
            List<DailyBucket> series, int remainingDays, LocalDate observedThrough,
            String currency, Instant now, CostAnalysisInputService.Input input) {
        var scope = Scope.valueOf(scopeType);
        var scopeId = scope == Scope.ORGANIZATION ? organizationId
                : Long.parseLong(scopeKey.substring(scopeKey.indexOf(':') + 1));
        var reviewed = input.days(scope, scopeId);
        if (!reviewed.isEmpty()) {
            series = series.stream().map(bucket -> new DailyBucket(bucket.date(), bucket.amount(), bucket.currency(),
                    bucket.observed() || reviewed.contains(bucket.date()))).toList();
        }
        if (series.size() < ForecastEngine.MIN_FALLBACK_BUCKETS) return;
        final ForecastEngine.Forecast forecast;
        try {
            forecast = ForecastEngine.forecast(series, remainingDays, observedThrough);
        } catch (IllegalArgumentException ex) {
            return;
        } catch (ForecastEngine.ForecastUnavailableException ex) {
            // Reversal-dominated history: no forecast row is persisted, so budget risk and
            // Advisor report forecastAvailable=false instead of a truncated zero estimate.
            return;
        }
        store.insertForecast(organizationId, runId, scopeType, scopeKey, currency,
                forecast.projectedAmount(), forecast.method(), forecast.historyBucketCount(),
                forecast.confidence(), forecast.observedThrough(), now);
    }

    private List<GrainSeries> includeReviewedGrains(List<GrainSeries> existing, CostAnalysisInputService.Input input,
            Scope scope, LocalDate start, LocalDate end, String currency) {
        var grains = new LinkedHashMap<String, GrainSeries>();
        existing.forEach(grain -> grains.put(grain.key(), grain));
        var prefix = scope.name().toLowerCase(java.util.Locale.ROOT) + ":";
        input.reviewedDays().keySet().stream().filter(key -> key.scope() == scope).forEach(key -> {
            var name = prefix + key.id();
            grains.putIfAbsent(name, new GrainSeries(name, key.id(), name,
                    completeSeries(Map.of(), start, end, currency)));
        });
        return List.copyOf(grains.values());
    }

    private List<GrainSeries> projectGrains(long organizationId, String currency, LocalDate start, LocalDate end) {
        return groupedSeries(facts.projectDaily(organizationId, currency, start, end).stream()
                .collect(LinkedHashMap<String, Map<LocalDate, BigDecimal>>::new,
                        (map, row) -> map.computeIfAbsent("project:" + row.scopeId(), key -> new LinkedHashMap<>())
                                .put(row.day(), row.amount()),
                        Map::putAll),
                start, end, currency);
    }

    private List<GrainSeries> providerGrains(long organizationId, String currency, LocalDate start, LocalDate end) {
        var grouped = new LinkedHashMap<String, LabeledMap>();
        for (var row : facts.providerDaily(organizationId, currency, start, end)) {
            grouped.computeIfAbsent("provider:" + row.scopeId(),
                    key -> new LabeledMap(row.scopeLabel(), new LinkedHashMap<>()))
                    .days().put(row.day(), row.amount());
        }
        return labeledSeries(grouped, start, end, currency);
    }

    private List<GrainSeries> modelGrains(long organizationId, String currency, LocalDate start, LocalDate end) {
        var grouped = new LinkedHashMap<String, Map<LocalDate, BigDecimal>>();
        for (var row : facts.modelDaily(organizationId, currency, start, end)) {
            grouped.computeIfAbsent(row.scopeLabel(), key -> new LinkedHashMap<>())
                    .put(row.day(), row.amount());
        }
        return groupedSeries(grouped, start, end, currency);
    }

    private List<GrainSeries> teamGrains(long organizationId, String currency, LocalDate start, LocalDate end) {
        var grouped = new LinkedHashMap<Long, Map<LocalDate, BigDecimal>>();
        for (var row : facts.teamDaily(organizationId, currency, start, end)) {
            grouped.computeIfAbsent(row.scopeId(), key -> new LinkedHashMap<>()).put(row.day(), row.amount());
        }
        return grouped.entrySet().stream()
                .map(entry -> new GrainSeries("team:" + entry.getKey(), entry.getKey(),
                        "team:" + entry.getKey(), completeSeries(entry.getValue(), start, end, currency)))
                .toList();
    }

    private List<GrainSeries> costCenterGrains(long organizationId, String currency, LocalDate start, LocalDate end) {
        var grouped = new LinkedHashMap<Long, Map<LocalDate, BigDecimal>>();
        for (var row : facts.costCenterDaily(organizationId, currency, start, end)) {
            grouped.computeIfAbsent(row.scopeId(), key -> new LinkedHashMap<>()).put(row.day(), row.amount());
        }
        return grouped.entrySet().stream()
                .map(entry -> new GrainSeries("cost_center:" + entry.getKey(), entry.getKey(),
                        "cost_center:" + entry.getKey(), completeSeries(entry.getValue(), start, end, currency)))
                .toList();
    }

    private List<GrainSeries> groupedSeries(Map<String, Map<LocalDate, BigDecimal>> grouped,
            LocalDate start, LocalDate end, String currency) {
        return grouped.entrySet().stream()
                .map(entry -> new GrainSeries(entry.getKey(), null, entry.getKey(),
                        completeSeries(entry.getValue(), start, end, currency)))
                .toList();
    }

    private List<GrainSeries> labeledSeries(Map<String, LabeledMap> grouped,
            LocalDate start, LocalDate end, String currency) {
        return grouped.entrySet().stream()
                .map(entry -> new GrainSeries(entry.getKey(), 0L, entry.getValue().label(),
                        completeSeries(entry.getValue().days(), start, end, currency)))
                .toList();
    }

    /** Keep anomaly calendar alignment, but do not treat missing ledger days as forecast evidence. */
    private List<DailyBucket> completeSeries(Map<LocalDate, BigDecimal> days,
            LocalDate start, LocalDate end, String currency) {
        var series = new ArrayList<DailyBucket>();
        for (var day = start; day.isBefore(end); day = day.plusDays(1)) {
            series.add(new DailyBucket(day, days.getOrDefault(day, BigDecimal.ZERO), currency,
                    days.containsKey(day)));
        }
        return series;
    }

    private int remainingDays(LocalDate analysisDate) {
        var monthEnd = analysisDate.withDayOfMonth(analysisDate.lengthOfMonth());
        return (int) Math.max(0, analysisDate.datesUntil(monthEnd.plusDays(1)).count() - 1);
    }

    private String driversJson(List<Contribution> drivers) {
        try {
            return objectMapper.writeValueAsString(drivers.stream()
                    .map(d -> Map.of("dimension", d.dimension(), "key", d.key(),
                            "delta", d.deltaAmount().toPlainString()))
                    .toList());
        } catch (Exception ex) {
            throw new IllegalStateException("Anomaly drivers are unavailable", ex);
        }
    }

    private LocalDate validatedDate(LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now(clock))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Cost intelligence validation failed", "Analysis date must not be in the future.");
        }
        return date;
    }

    private record GrainSeries(String key, Long scopeId, String label, List<DailyBucket> series) {
    }

    private record LabeledMap(String label, Map<LocalDate, BigDecimal> days) {
    }
}
