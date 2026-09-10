package com.aicostops.intelligence.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.domain.BudgetRiskService;
import com.aicostops.intelligence.domain.CostAnomalyEngine;
import com.aicostops.intelligence.domain.CostAnomalyEngine.Contribution;
import com.aicostops.intelligence.domain.CostAnomalyEngine.DailyBucket;
import com.aicostops.intelligence.domain.ForecastEngine;
import com.aicostops.intelligence.domain.SavingsEngine;
import com.aicostops.intelligence.domain.SavingsEngine.PricedRate;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.CostFactsMapper.BudgetRow;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic Cost Intelligence orchestration (M18 V3).
 *
 * <p>Runs are DB-converged (one logical run per org/date/currency/version
 * across replicas). All money derives from settled Gateway truth; results
 * are DERIVED evidence, never Ledger truth. Currencies never mix.
 */
@Service
public class CostIntelligenceService {

    public static final int RUN_VERSION = 1;
    private static final int HISTORY_DAYS = 29;
    private static final BigDecimal SAVING_PERCENT_THRESHOLD = new BigDecimal("5");

    private static final Map<String, BigDecimal> MATERIALITY = Map.of(
            "USD", new BigDecimal("5.00"), "EUR", new BigDecimal("5.00"),
            "CNY", new BigDecimal("30.00"), "GBP", new BigDecimal("4.00"));

    private final AuthorizationContextService authorizationContexts;
    private final CostFactsMapper facts;
    private final IntelligenceMapper store;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public CostIntelligenceService(
            AuthorizationContextService authorizationContexts,
            CostFactsMapper facts,
            IntelligenceMapper store,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.facts = facts;
        this.store = store;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Runs (or converges on) one deterministic analysis. Scheduler + API share this. */
    @Transactional
    public RunOutcome runAnalysis(long organizationId, LocalDate analysisDate, String currency) {
        var now = clock.instant();
        store.insertRunIfMissing(organizationId, analysisDate, currency, RUN_VERSION, now);
        if (store.claimRun(organizationId, analysisDate, currency, RUN_VERSION, now) != 1) {
            return new RunOutcome(store.findRunId(organizationId, analysisDate, currency, RUN_VERSION),
                    store.findRunStatus(organizationId, analysisDate, currency, RUN_VERSION));
        }
        var runId = store.findRunId(organizationId, analysisDate, currency, RUN_VERSION);
        try {
            analyze(organizationId, runId, analysisDate, currency, now);
            store.finishRun(organizationId, analysisDate, currency, RUN_VERSION, "COMPLETED", null, now);
            return new RunOutcome(runId, "COMPLETED");
        } catch (RuntimeException ex) {
            store.finishRun(organizationId, analysisDate, currency, RUN_VERSION, "FAILED",
                    ex.getClass().getSimpleName(), now);
            throw ex;
        }
    }

    public RunOutcome runAnalysis(AuthenticatedUser user, LocalDate analysisDate, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        return runAnalysis(context.organizationId(), validatedDate(analysisDate), validatedCurrency(currency));
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
                    CostAnomalyEngine.detect("PROJECT", "project:" + grain.scopeId(), grain.series(),
                            List.of(), materiality),
                    now);
        }
        for (var grain : providerGrains(organizationId, currency, start, end)) {
            persistAnomalies(organizationId, runId,
                    CostAnomalyEngine.detect("PROVIDER", "provider:" + grain.scopeId(), grain.series(),
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
            persistForecast(organizationId, runId, "PROJECT", "project:" + grain.scopeId(),
                    grain.series(), remaining, observedThrough, currency, now);
        }
        buildSavings(organizationId, runId, start, end, currency, materiality, now);
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

    private void buildSavings(long organizationId, long runId, LocalDate start, LocalDate end,
            String currency, BigDecimal materiality, Instant now) {
        var byModel = new HashMap<Long, List<CostFactsMapper.PricingCandidate>>();
        for (var modelId : facts.distinctPricedLogicalModels(organizationId, currency, now)) {
            for (var candidate : facts.pricingCandidates(organizationId, currency, modelId, now)) {
                byModel.computeIfAbsent(candidate.logicalModelId(), key -> new ArrayList<>()).add(candidate);
            }
        }
        for (var entry : byModel.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            compareCandidates(organizationId, runId, entry.getKey(), entry.getValue(),
                    start, end, currency, materiality, now);
        }
    }

    private void compareCandidates(long organizationId, long runId, long logicalModelId,
            List<CostFactsMapper.PricingCandidate> candidates, LocalDate start, LocalDate end,
            String currency, BigDecimal materiality, Instant now) {
        var replayed = new ArrayList<ReplayedCandidate>();
        for (var candidate : candidates) {
            var usage = usageMap(organizationId, currency, start, end,
                    candidate.accountId(), candidate.modelId());
            if (usage.isEmpty()) continue;
            var rates = rateMap(organizationId, candidate.pricingVersionId());
            if (rates.isEmpty()) continue;
            replayed.add(new ReplayedCandidate(candidate, usage, rates,
                    SavingsEngine.replay(usage, rates)));
        }
        if (replayed.size() < 2) return;
        replayed.sort(Comparator.comparing(ReplayedCandidate::cost).reversed());
        var current = replayed.get(0);
        for (var challenger : replayed.subList(1, replayed.size())) {
            var comparison = SavingsEngine.compare(logicalModelId, logicalModelId, logicalModelId,
                    current.usage(), current.rates(), challenger.rates());
            if (comparison.potentialSaving().compareTo(materiality) <= 0
                    || comparison.potentialSavingPercent().compareTo(SAVING_PERCENT_THRESHOLD) < 0) {
                continue;
            }
            store.insertRecommendation(organizationId, runId, logicalModelId,
                    current.candidate().accountId(), current.candidate().modelId(),
                    current.candidate().pricingVersionId(), challenger.candidate().accountId(),
                    challenger.candidate().modelId(), challenger.candidate().pricingVersionId(),
                    currency, start, end.minusDays(1), comparison.currentCost(),
                    comparison.candidateCost(), comparison.potentialSaving(),
                    comparison.potentialSavingPercent(),
                    fingerprint(logicalModelId, start, end, current, challenger, comparison),
                    now);
        }
    }

    private Map<String, Long> usageMap(long organizationId, String currency, LocalDate start,
            LocalDate end, long accountId, long modelId) {
        var usage = new LinkedHashMap<String, Long>();
        for (var row : facts.usageTotals(organizationId, currency, start, end, accountId, modelId)) {
            if (row.quantity() != null && row.quantity().compareTo(BigDecimal.ZERO) > 0) {
                usage.put(row.dimensionCode(), row.quantity().longValue());
            }
        }
        return usage;
    }

    private Map<String, PricedRate> rateMap(long organizationId, long pricingVersionId) {
        var rates = new LinkedHashMap<String, PricedRate>();
        for (var row : facts.pricingRates(organizationId, pricingVersionId)) {
            rates.put(row.dimensionCode(), new PricedRate(row.unitQuantity(), row.unitPrice()));
        }
        return rates;
    }

    private String fingerprint(long logicalModelId, LocalDate start, LocalDate end,
            ReplayedCandidate current, ReplayedCandidate challenger,
            SavingsEngine.Comparison comparison) {
        try {
            var canonical = logicalModelId + "|" + start + "|" + end + "|"
                    + current.candidate().pricingVersionId() + "|"
                    + challenger.candidate().pricingVersionId() + "|"
                    + current.usage() + "|" + comparison.currentCost().toPlainString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Savings fingerprint is unavailable", ex);
        }
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

    private String validatedCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Cost intelligence validation failed", "Currency must be ISO-4217.");
        }
        return currency;
    }

    public SummaryResponse summary(AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate, validatedCurrency(currency),
                RUN_VERSION);
        if (runId == null) {
            return new SummaryResponse(null, "STALE", 0, 0, 0);
        }
        var status = store.findRunStatus(context.organizationId(), runDate, currency, RUN_VERSION);
        return new SummaryResponse(runId, status,
                store.listAnomalies(context.organizationId(), runId).size(),
                store.listForecasts(context.organizationId(), runId).size(),
                (int) store.listRecommendations(context.organizationId(), runId).stream()
                        .filter(r -> "OPEN".equals(r.status())).count());
    }

    public List<AnomalyResponse> anomalies(AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate, validatedCurrency(currency),
                RUN_VERSION);
        if (runId == null) return List.of();
        return store.listAnomalies(context.organizationId(), runId).stream()
                .map(r -> new AnomalyResponse(r.id(), r.grainType(), r.grainKey(), r.currency(),
                        r.observedAmount().toPlainString(), r.baselineAmount().toPlainString(),
                        r.deltaAmount().toPlainString(), r.deltaPercent().toPlainString(),
                        r.robustZScore(), r.driversJson()))
                .toList();
    }

    public List<ForecastResponse> forecasts(AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate, validatedCurrency(currency),
                RUN_VERSION);
        if (runId == null) return List.of();
        return store.listForecasts(context.organizationId(), runId).stream()
                .map(r -> new ForecastResponse(r.scopeType(), r.scopeKey(), r.currency(),
                        r.projectedAmount().toPlainString(), r.method(), r.historyBucketCount(),
                        r.confidence(), r.observedThrough()))
                .toList();
    }

    public BudgetRiskResponse budgetRisk(AuthenticatedUser user, String scopeType, long scopeId, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "BUDGET_READ");
        var budget = facts.openBudget(context.organizationId(), scopeType, scopeId,
                validatedCurrency(currency));
        if (budget == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Budget not found", "No OPEN budget covers this scope and currency.");
        }
        var reservations = facts.activeReservations(context.organizationId(), budget.id());
        var forecast = latestForecastAmount(context.organizationId(), scopeType, scopeId, currency);
        var assessment = BudgetRiskService.assess(budget.actualAmount(), budget.committedAmount(),
                reservations == null ? BigDecimal.ZERO : reservations, forecast, budget.totalAmount(),
                currency);
        return new BudgetRiskResponse(assessment.immediateExposure().toPlainString(),
                assessment.projectedPeriodEnd().toPlainString(), budget.totalAmount().toPlainString(),
                currency, assessment.risk());
    }

    private BigDecimal latestForecastAmount(long organizationId, String scopeType, long scopeId, String currency) {
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(organizationId, runDate, currency, RUN_VERSION);
        if (runId == null) return BigDecimal.ZERO;
        var key = "PROJECT".equals(scopeType) ? ("project:" + scopeId) : ("org:" + organizationId);
        return store.listForecasts(organizationId, runId).stream()
                .filter(r -> r.scopeKey().equals(key))
                .map(IntelligenceMapper.ForecastRow::projectedAmount)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    public List<RecommendationResponse> recommendations(AuthenticatedUser user, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(context.organizationId(), runDate, validatedCurrency(currency),
                RUN_VERSION);
        if (runId == null) return List.of();
        return store.listRecommendations(context.organizationId(), runId).stream()
                .map(this::recommendationResponse).toList();
    }

    @Transactional
    public RecommendationResponse acknowledge(AuthenticatedUser user, long id) {
        return transition(user, id, "OPEN", "ACKNOWLEDGED", null, "SAVINGS_RECOMMENDATION_ACKNOWLEDGED");
    }

    @Transactional
    public RecommendationResponse dismiss(AuthenticatedUser user, long id) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        var current = recommendation(context.organizationId(), id);
        if (!"OPEN".equals(current.status()) && !"ACKNOWLEDGED".equals(current.status())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Recommendation state conflict", "Only OPEN or ACKNOWLEDGED can be dismissed.");
        }
        return transition(user, id, current.status(), "DISMISSED", null,
                "SAVINGS_RECOMMENDATION_DISMISSED");
    }

    @Transactional
    public RecommendationResponse markApplied(AuthenticatedUser user, long id, Long routingPolicyId) {
        return transition(user, id, "ACKNOWLEDGED", "APPLIED", routingPolicyId,
                "SAVINGS_RECOMMENDATION_APPLIED");
    }

    private RecommendationResponse transition(AuthenticatedUser user, long id, String from, String to,
            Long policyId, String auditEvent) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "COST_READ");
        if (store.transitionRecommendation(id, context.organizationId(), from, to, policyId) != 1) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Recommendation state conflict", "The recommendation is not in a mutable state.");
        }
        audit.append(auditEvent, context.organizationId(), user.userId(),
                "SAVINGS_RECOMMENDATION", id, Map.of("from", from, "to", to));
        return recommendationResponse(recommendation(context.organizationId(), id));
    }

    private IntelligenceMapper.RecommendationRow recommendation(long organizationId, long id) {
        var row = store.findRecommendation(id, organizationId);
        if (row == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Recommendation not found", "The recommendation is not available.");
        }
        return row;
    }

    private RecommendationResponse recommendationResponse(IntelligenceMapper.RecommendationRow row) {
        return new RecommendationResponse(row.id(), row.logicalModelId(), row.currentProviderAccountId(),
                row.candidateProviderAccountId(), row.candidateProviderModelId(), row.currency(),
                row.currentCost().toPlainString(), row.candidateCost().toPlainString(),
                row.potentialSaving().toPlainString(), row.potentialSavingPercent().toPlainString(),
                row.status(), row.routingPolicyId(), row.calculatedAt());
    }

    private record GrainSeries(String key, Long scopeId, String label, List<DailyBucket> series) {
    }

    private record LabeledMap(String label, Map<LocalDate, BigDecimal> days) {
    }

    private record ReplayedCandidate(
            CostFactsMapper.PricingCandidate candidate, Map<String, Long> usage,
            Map<String, PricedRate> rates, BigDecimal cost) {
    }

    public record RunOutcome(Long runId, String status) {
    }

    public record SummaryResponse(Long runId, String status, int anomalyCount, int forecastCount,
            int openRecommendations) {
    }

    public record AnomalyResponse(long id, String grainType, String grainKey, String currency,
            String observedAmount, String baselineAmount, String deltaAmount, String deltaPercent,
            double robustZScore, String driversJson) {
    }

    public record ForecastResponse(String scopeType, String scopeKey, String currency,
            String projectedAmount, String method, int historyBucketCount, String confidence,
            LocalDate observedThrough) {
    }

    public record BudgetRiskResponse(String immediateExposure, String projectedPeriodEnd,
            String budgetTotal, String currency, String risk) {
    }

    public record RecommendationResponse(long id, long logicalModelId, long currentProviderAccountId,
            long candidateProviderAccountId, long candidateProviderModelId, String currency,
            String currentCost, String candidateCost, String potentialSaving,
            String potentialSavingPercent, String status, Long routingPolicyId, Instant calculatedAt) {
    }
}
