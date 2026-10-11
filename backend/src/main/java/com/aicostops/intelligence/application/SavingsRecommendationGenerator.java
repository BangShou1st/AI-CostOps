package com.aicostops.intelligence.application;

import com.aicostops.intelligence.domain.SavingsEngine;
import com.aicostops.intelligence.domain.SavingsEngine.PricedRate;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic savings-recommendation generation (M18 V3 round-3 P1).
 *
 * <p>Runs inside the run transaction's analysis callback: recommendations are derived results of
 * the same run and are never committed independently. The workflow reads the pricing candidates of
 * one logical model, replays the real source-route usage vector through every other
 * production-ready rate card and persists only strictly cheaper, material challengers. No
 * {@code REQUIRES_NEW} boundary exists here on purpose — a failure must roll the whole run back.
 */
@Component
public class SavingsRecommendationGenerator {

    private static final BigDecimal SAVING_PERCENT_THRESHOLD = new BigDecimal("5");

    private final CostFactsMapper facts;
    private final IntelligenceMapper store;

    public SavingsRecommendationGenerator(CostFactsMapper facts, IntelligenceMapper store) {
        this.facts = facts;
        this.store = store;
    }

    /** Generates and persists every recommendable lowering for one analysis run. */
    public void generate(long organizationId, long runId, LocalDate start, LocalDate end,
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

    /**
     * P1: counterfactual replay from the real current/source route usage vector. The source is
     * the candidate with the largest observed historical cost (the actual spend route); that same
     * usage vector is replayed through every other production-ready candidate rate card. A cheaper
     * candidate with zero historical usage of its own is still recommendable; candidates without
     * readiness (filtered in pricingCandidates) never reach here.
     */
    private void compareCandidates(long organizationId, long runId, long logicalModelId,
            List<CostFactsMapper.PricingCandidate> candidates, LocalDate start, LocalDate end,
            String currency, BigDecimal materiality, Instant now) {
        var withRates = new ArrayList<ReplayedCandidate>();
        for (var candidate : candidates) {
            var rates = rateMap(organizationId, candidate.pricingVersionId());
            if (rates.isEmpty()) continue;
            var ownUsage = usageMap(organizationId, currency, start, end,
                    candidate.accountId(), candidate.modelId());
            var ownCost = ownUsage.isEmpty() ? null : SavingsEngine.replay(ownUsage, rates);
            withRates.add(new ReplayedCandidate(candidate, ownUsage, rates, ownCost));
        }
        if (withRates.size() < 2) return;
        var sourced = withRates.stream().filter(c -> c.cost() != null).toList();
        if (sourced.isEmpty()) return;
        var current = sourced.stream().max(Comparator.comparing(ReplayedCandidate::cost)).orElseThrow();
        if (current.usage().isEmpty()) return;
        if (!SavingsEngine.isReplayable(current.usage(), current.rates())) {
            return;
        }
        for (var challenger : withRates) {
            if (challenger.candidate().pricingVersionId() == current.candidate().pricingVersionId()) {
                continue;
            }
            if (!SavingsEngine.isReplayable(current.usage(), challenger.rates())) {
                continue;
            }
            var comparison = SavingsEngine.compare(logicalModelId, logicalModelId, logicalModelId,
                    current.usage(), current.rates(), challenger.rates());
            if (comparison.potentialSaving().compareTo(materiality) <= 0
                    || comparison.potentialSavingPercent().compareTo(SAVING_PERCENT_THRESHOLD) < 0) {
                continue;
            }
            // Only emit when challenger is strictly cheaper on the identical usage vector.
            if (comparison.candidateCost().compareTo(comparison.currentCost()) >= 0) {
                continue;
            }
            // A production-ready candidate that is not yet referenced by an ACTIVE routing
            // revision stays recommendable; the flag tells clients a routing change is required
            // before APPLIED can link it.
            var routed = facts.countActiveRoutingReferences(organizationId,
                    challenger.candidate().accountId(), challenger.candidate().modelId(),
                    logicalModelId) > 0;
            store.insertRecommendation(organizationId, runId, logicalModelId,
                    current.candidate().accountId(), current.candidate().modelId(),
                    current.candidate().pricingVersionId(), challenger.candidate().accountId(),
                    challenger.candidate().modelId(), challenger.candidate().pricingVersionId(),
                    currency, start, end.minusDays(1), comparison.currentCost(),
                    comparison.candidateCost(), comparison.potentialSaving(),
                    comparison.potentialSavingPercent(),
                    fingerprint(logicalModelId, start, end, current, challenger, comparison),
                    !routed, now);
        }
    }

    /** Usage quantities stay BigDecimal end to end (DECIMAL(30,8)); truncation to long is forbidden. */
    private Map<String, BigDecimal> usageMap(long organizationId, String currency, LocalDate start,
            LocalDate end, long accountId, long modelId) {
        var usage = new LinkedHashMap<String, BigDecimal>();
        for (var row : facts.usageTotals(organizationId, currency, start, end, accountId, modelId)) {
            if (row.quantity() != null && row.quantity().compareTo(BigDecimal.ZERO) > 0) {
                usage.put(row.dimensionCode(), row.quantity());
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

    /**
     * Evidence fingerprint: input order, map iteration order and money text are frozen so a
     * Gateway-side recomputation still matches. Never "upgrade" the format here.
     */
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

    private record ReplayedCandidate(
            CostFactsMapper.PricingCandidate candidate, Map<String, BigDecimal> usage,
            Map<String, PricedRate> rates, BigDecimal cost) {
    }
}
