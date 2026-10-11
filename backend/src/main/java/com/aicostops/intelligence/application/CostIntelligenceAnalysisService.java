package com.aicostops.intelligence.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.domain.CostAnomalyEngine;
import com.aicostops.intelligence.domain.CostAnomalyEngine.Contribution;
import com.aicostops.intelligence.domain.CostAnomalyEngine.DailyBucket;
import com.aicostops.intelligence.domain.ForecastEngine;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
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

    private static final int HISTORY_DAYS = 29;

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
                runId -> analyze(organizationId, runId, analysisDate, currency, clock.instant()));
    }

    public AnalysisRunOutcome runAnalysis(AuthenticatedUser user, LocalDate analysisDate, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        return runAnalysis(context.organizationId(), validatedDate(analysisDate),
                CostIntelligenceValidation.validatedCurrency(currency));
    }

    /** Scheduler entry: recently active orgs x settled currencies for yesterday. */
    public void runDueAnalyses() {
        var yesterday = LocalDate.now(clock).minusDays(1);
        for (var orgId : facts.recentlyActiveOrgs(yesterday.minusDays(30), 100)) {
            for (var currency : facts.settledCurrencies(orgId)) {
                try {
                    runAnalysis(orgId, yesterday, currency);
                } catch (RuntimeException ignored) {
                    // One org/currency failure never blocks the remaining runs;
                    // the FAILED run row records the outcome for operators.
                }
            }
        }
    }

    private void analyze(long organizationId, long runId, LocalDate analysisDate, String currency, Instant now) {
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
                orgSeries, remaining, observedThrough, currency, now);
        for (var grain : projectGrains(organizationId, currency, start, end)) {
            persistForecast(organizationId, runId, "PROJECT", grain.key(),
                    grain.series(), remaining, observedThrough, currency, now);
        }
        // P1: TEAM / COST_CENTER scoped forecast grains (never fall back to whole-org).
        for (var grain : teamGrains(organizationId, currency, start, end)) {
            persistForecast(organizationId, runId, "TEAM", grain.key(),
                    grain.series(), remaining, observedThrough, currency, now);
        }
        for (var grain : costCenterGrains(organizationId, currency, start, end)) {
            persistForecast(organizationId, runId, "COST_CENTER", grain.key(),
                    grain.series(), remaining, observedThrough, currency, now);
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
            String currency, Instant now) {
        if (series.size() < ForecastEngine.MIN_FALLBACK_BUCKETS) return;
        final ForecastEngine.Forecast forecast;
        try {
            forecast = ForecastEngine.forecast(series, remainingDays, observedThrough);
        } catch (IllegalArgumentException ex) {
            return;
        }
        store.insertForecast(organizationId, runId, scopeType, scopeKey, currency,
                forecast.projectedAmount(), forecast.method(), forecast.historyBucketCount(),
                forecast.confidence(), forecast.observedThrough(), now);
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

    /** Whole window is zero-filled day by day; a gap is a real zero, never a missing bucket. */
    private List<DailyBucket> completeSeries(Map<LocalDate, BigDecimal> days,
            LocalDate start, LocalDate end, String currency) {
        var series = new ArrayList<DailyBucket>();
        for (var day = start; day.isBefore(end); day = day.plusDays(1)) {
            series.add(new DailyBucket(day, days.getOrDefault(day, BigDecimal.ZERO), currency));
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
