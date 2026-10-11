package com.aicostops.intelligence.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.intelligence.domain.BudgetRiskService;
import com.aicostops.intelligence.infrastructure.CostFactsMapper;
import com.aicostops.intelligence.infrastructure.IntelligenceMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Budget-risk projection for the HTTP read entry point and the Advisor evidence entry point
 * (M18 V3).
 *
 * <p>Two deliberately distinct entry points: {@link #budgetRisk} authorizes organization-level
 * BUDGET_READ and resolves the OPEN budget itself, while {@link #assessScopeBudget} accepts budget
 * amounts the caller already authorized (the Advisor's frozen subject row) and only adds the
 * reservations and scoped forecast. Neither publishes an HTTP route for the internal entry, and
 * neither bypasses the Advisor's subject authorization. Risk thresholds stay in
 * {@link BudgetRiskService}.
 */
@Service
public class BudgetRiskQueryService {

    private final AuthorizationContextService authorizationContexts;
    private final CostFactsMapper facts;
    private final IntelligenceMapper store;
    private final Clock clock;
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public BudgetRiskQueryService(AuthorizationContextService authorizationContexts,
            CostFactsMapper facts, IntelligenceMapper store, Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.facts = facts;
        this.store = store;
        this.clock = clock;
    }

    public CostIntelligenceReadModels.BudgetRiskResponse budgetRisk(AuthenticatedUser user,
            String scopeType, long scopeId, String currency) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, "BUDGET_READ");
        var budget = facts.openBudget(context.organizationId(), scopeType, scopeId,
                CostIntelligenceValidation.validatedCurrency(currency));
        if (budget == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Budget not found", "No OPEN budget covers this scope and currency.");
        }
        var reservations = facts.activeReservations(context.organizationId(), budget.id());
        var forecast = latestForecastAmount(context.organizationId(), scopeType, scopeId, currency);
        var assessment = BudgetRiskService.assess(budget.actualAmount(), budget.committedAmount(),
                zeroIfNull(reservations), forecast, budget.totalAmount(), currency);
        return new CostIntelligenceReadModels.BudgetRiskResponse(
                assessment.immediateExposure().toPlainString(),
                assessment.projectedPeriodEnd().toPlainString(), budget.totalAmount().toPlainString(),
                currency, assessment.risk());
    }

    /**
     * Deterministic budget-risk evidence for Advisor snapshots: the model explains these
     * precomputed values and must never recompute projected spend or risk classification.
     * Reservations are included in immediate exposure only, never extrapolated.
     */
    public BudgetRiskEvidence assessScopeBudget(long organizationId, String scopeType, long scopeId,
            BigDecimal actual, BigDecimal committed, BigDecimal total, String currency, long budgetId) {
        var reservations = zeroIfNull(facts.activeReservations(organizationId, budgetId));
        var forecast = latestForecastAmount(organizationId, scopeType, scopeId, currency);
        var assessment = BudgetRiskService.assess(actual, committed, reservations, forecast, total,
                currency);
        return new BudgetRiskEvidence(actual, committed, reservations, forecast,
                assessment.immediateExposure(), assessment.projectedPeriodEnd(), total, currency,
                assessment.risk());
    }

    /**
     * P1: scoped forecast lookup. TEAM/COST_CENTER use their own grains; missing scoped
     * history yields ZERO future usage (never whole-org fallback which misattributes spend).
     */
    private BigDecimal latestForecastAmount(long organizationId, String scopeType, long scopeId,
            String currency) {
        var runDate = LocalDate.now(clock).minusDays(1);
        var runId = store.findRunId(organizationId, runDate, currency,
                CostIntelligenceValidation.RUN_VERSION);
        if (runId == null) return BigDecimal.ZERO;
        final String key = switch (scopeType) {
            case "PROJECT" -> "project:" + scopeId;
            case "TEAM" -> "team:" + scopeId;
            case "COST_CENTER" -> "cost_center:" + scopeId;
            default -> "org:" + organizationId;
        };
        return store.listForecasts(organizationId, runId).stream()
                .filter(r -> r.scopeKey().equals(key))
                .map(IntelligenceMapper.ForecastRow::projectedAmount)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
