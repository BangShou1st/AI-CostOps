package com.aicostops.advisor.application;

import static com.aicostops.advisor.application.AdvisorErrors.notFound;
import static com.aicostops.advisor.application.AdvisorErrors.validationFailed;

import com.aicostops.advisor.infrastructure.AdvisorEvidenceMapper;
import com.aicostops.advisor.infrastructure.AdvisorRows;
import com.aicostops.intelligence.application.BudgetRiskQueryService;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds bounded evidence from organization-scoped persisted subjects.
 *
 * <p>The caller must authorize the user first. This component performs no writes or
 * Provider I/O and adds no transaction: AdvisorJobCommandService owns the request transaction.
 */
@Component
public class AdvisorEvidenceAssembler {

    private final AdvisorEvidenceMapper mapper;
    private final BudgetRiskQueryService budgetRisks;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdvisorEvidenceAssembler(AdvisorEvidenceMapper mapper, BudgetRiskQueryService budgetRisks,
            ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.budgetRisks = budgetRisks;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    AdvisorEvidence.Envelope build(long organizationId, String subjectType, long subjectId) {
        return switch (subjectType) {
            case "ANOMALY" -> {
                var row = mapper.findAnomalySubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                var facts = List.of(
                        new AdvisorEvidence.MoneyFact("anomaly:" + row.id() + ":observed", "observed",
                                row.observedAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("anomaly:" + row.id() + ":baseline", "baseline",
                                row.baselineAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("anomaly:" + row.id() + ":delta", "delta",
                                row.deltaAmount(), row.currency()));
                // P1: drivers come from the persisted deterministic contribution analysis, never
                // from the grain itself; empty stays explicitly empty for the model.
                yield AdvisorEvidence.build("ANOMALY", row.id(), row.currency(), facts,
                        parseAnomalyDrivers(row), "", "", "", clock.instant());
            }
            case "FORECAST" -> {
                var row = mapper.findForecastSubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                var facts = List.of(new AdvisorEvidence.MoneyFact("forecast:" + row.id() + ":projected",
                        "projected", row.projectedAmount(), row.currency()));
                yield AdvisorEvidence.build("FORECAST", row.id(), row.currency(), facts, List.of(),
                        "method=" + row.method(), "", "", clock.instant());
            }
            case "SAVINGS" -> {
                var row = mapper.findSavingsSubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                var facts = List.of(
                        new AdvisorEvidence.MoneyFact("savings:" + row.id() + ":current", "current-cost",
                                row.currentCost(), row.currency()),
                        new AdvisorEvidence.MoneyFact("savings:" + row.id() + ":candidate", "candidate-cost",
                                row.candidateCost(), row.currency()),
                        new AdvisorEvidence.MoneyFact("savings:" + row.id() + ":saving", "potential-saving",
                                row.potentialSaving(), row.currency()));
                yield AdvisorEvidence.build("SAVINGS", row.id(), row.currency(), facts, List.of(),
                        "", "", "", clock.instant());
            }
            case "BUDGET_RISK" -> {
                var row = mapper.findBudgetSubject(subjectId, organizationId);
                if (row == null) {
                    throw notFound("Advisor subject was not found.");
                }
                // P1: risk inputs and classification are precomputed deterministically here and
                // frozen into the snapshot; the model only explains them.
                var evidence = budgetRisks.assessScopeBudget(organizationId, row.scopeType(),
                        row.scopeId(), row.actualAmount(), row.committedAmount(), row.totalAmount(),
                        row.currency(), row.id());
                var facts = List.of(
                        new AdvisorEvidence.MoneyFact("budget:" + row.id() + ":actual", "actual",
                                row.actualAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("budget:" + row.id() + ":committed", "committed",
                                row.committedAmount(), row.currency()),
                        new AdvisorEvidence.MoneyFact("budget:" + row.id() + ":total", "total",
                                row.totalAmount(), row.currency()));
                final String riskBlock;
                try {
                    riskBlock = objectMapper.writeValueAsString(java.util.Map.of(
                            "scope", row.scopeType() + ":" + row.scopeId(),
                            "actual", evidence.actual().toPlainString(),
                            "committed", evidence.committed().toPlainString(),
                            "reservations", evidence.reservations().toPlainString(),
                            "forecastFuture", evidence.forecastFutureUsage().toPlainString(),
                            "immediate", evidence.immediateExposure().toPlainString(),
                            "projected", evidence.projectedPeriodEnd().toPlainString(),
                            "total", evidence.budgetTotal().toPlainString(),
                            "risk", evidence.risk()));
                } catch (Exception ex) {
                    throw new IllegalStateException("Advisor risk evidence is unavailable", ex);
                }
                yield AdvisorEvidence.build("BUDGET_RISK", row.id(), row.currency(), facts, List.of(),
                        "", riskBlock, "", clock.instant());
            }
            default -> throw validationFailed("Subject type must be ANOMALY, FORECAST, BUDGET_RISK or SAVINGS.");
        };
    }

    /**
     * Parses the persisted deterministic contribution analysis ({@code [{dimension,key,delta}]})
     * into bounded drivers. Corrupt or absent driver data yields an explicitly empty list so the
     * model never invents drivers; money stays BigDecimal.
     */
    private List<AdvisorEvidence.Driver> parseAnomalyDrivers(AdvisorRows.AnomalySubject row) {
        var drivers = new java.util.ArrayList<AdvisorEvidence.Driver>();
        try {
            var raw = row.driversJson();
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            var node = objectMapper.readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            for (var item : node) {
                if (drivers.size() >= AdvisorEvidence.MAX_DRIVERS) {
                    break;
                }
                var dimension = item.path("dimension").asText("").strip();
                var key = item.path("key").asText("").strip();
                var deltaText = item.path("delta").asText("").strip();
                if (dimension.isEmpty() || key.isEmpty() || deltaText.isEmpty()) {
                    continue;
                }
                drivers.add(new AdvisorEvidence.Driver(dimension, key,
                        new java.math.BigDecimal(deltaText), row.currency()));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.copyOf(drivers);
    }



}
