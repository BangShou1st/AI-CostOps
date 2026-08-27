package com.aicostops.reconciliation.application;

import com.aicostops.budget.application.BillingPeriodClosePort;
import com.aicostops.budget.domain.BillingPeriod;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.reconciliation.application.PeriodCloseReadModels.PeriodCloseView;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.domain.PeriodCloseCheckResult;
import com.aicostops.reconciliation.domain.PeriodCloseRun;
import com.aicostops.reconciliation.domain.PeriodCloseRunStatus;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.PeriodCloseMapper;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public final class PeriodCloseService {

    private static final String PERMISSION_CLOSE = "PERIOD_CLOSE";
    private static final String PERMISSION_REOPEN = "PERIOD_REOPEN";
    private static final String CLOSE_ERROR_CODE = "CLOSE_BLOCKER_ERROR";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodClosePort periods;
    private final PeriodCloseMapper closeMapper;
    private final ReconciliationMapper reconciliationMapper;
    private final CloseBlockerRegistry blockers;
    private final ReconciliationAuditPort audit;
    private final ObjectMapper objectMapper;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PeriodCloseService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodClosePort periods,
            PeriodCloseMapper closeMapper,
            ReconciliationMapper reconciliationMapper,
            CloseBlockerRegistry blockers,
            ReconciliationAuditPort audit,
            ObjectMapper objectMapper,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.periods = periods;
        this.closeMapper = closeMapper;
        this.reconciliationMapper = reconciliationMapper;
        this.blockers = blockers;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public PeriodCloseView close(AuthenticatedUser user, long periodId) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_CLOSE);
        var begun = beginOrResume(context, periodId);
        if (begun.alreadyTerminal()) {
            return view(begun.period(), begun.run());
        }

        var blockerContext = new CloseBlockerContext(
                context.organizationId(), begun.period().id(),
                begun.period().periodStart(), begun.period().periodEnd());
        var results = evaluateAll(blockerContext);
        var closeView = finalizeClose(context, begun.run().id(), results);
        metrics.periodClose(closeView.run().status() == PeriodCloseRunStatus.CLOSED
                ? "CLOSED" : "BLOCKED");
        return closeView;
    }

    BeginResult beginOrResume(AuthorizationContext context, long periodId) {
        var result = transactions.execute(status -> {
            periods.lockOrganizationAdmission(context.organizationId());
            var period = periods.lockPeriod(context.organizationId(), periodId);

            if (period.status() == BillingPeriodStatus.CLOSED) {
                var successful = closeMapper.selectLatestSuccessfulRunForGeneration(
                        context.organizationId(), period.id(), period.closeGeneration());
                if (successful == null) {
                    throw conflict("A CLOSED billing period has no successful CloseRun for its current generation.");
                }
                return new BeginResult(period, successful, true);
            }

            if (period.status() == BillingPeriodStatus.CLOSING) {
                var checking = closeMapper.selectCheckingRunsForGeneration(
                        context.organizationId(), period.id(), period.closeGeneration());
                if (checking.size() != 1) {
                    throw conflict("A CLOSING billing period must have exactly one CHECKING CloseRun.");
                }
                return new BeginResult(period, checking.getFirst(), false);
            }

            if (period.status() != BillingPeriodStatus.OPEN) {
                throw conflict("The billing period cannot begin Close from status " + period.status() + ".");
            }

            var attemptNo = closeMapper.selectNextAttemptNo(
                    context.organizationId(), period.id(), period.closeGeneration());
            var now = clock.instant();
            if (closeMapper.insertRun(context.organizationId(), period.id(),
                    period.closeGeneration(), attemptNo, PeriodCloseRunStatus.CHECKING.name(),
                    null, context.organizationMemberId(), now, now, now) != 1) {
                throw new IllegalStateException("CloseRun insertion must affect exactly one row");
            }
            var closeRunId = closeMapper.lastInsertId();
            var closingPeriod = periods.markClosing(context.organizationId(), period.id(),
                    period.version(), now);
            var closeRun = closeMapper.selectRunByIdAndOrganization(
                    context.organizationId(), closeRunId);
            if (closeRun == null) {
                throw new IllegalStateException("A just-created CloseRun must be readable");
            }
            audit.closeStarted(context.organizationId(), context.userId(), closeRunId,
                    period.id(), period.closeGeneration(), attemptNo);
            return new BeginResult(closingPeriod, closeRun, false);
        });
        if (result == null) {
            throw new IllegalStateException("Close begin transaction returned no result");
        }
        return result;
    }

    public PeriodCloseView reopen(
            AuthenticatedUser user, long periodId, ReopenPeriodCommand command) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_REOPEN);
        var reasonCode = requireBounded(command == null ? null : command.reasonCode(), 100, "reasonCode");
        var reasonNote = requireBounded(command == null ? null : command.reasonNote(), 2000, "reasonNote");

        var result = transactions.execute(status -> {
            var period = periods.lockPeriod(context.organizationId(), periodId);
            if (period.status() != BillingPeriodStatus.CLOSED) {
                throw conflict("Only a CLOSED billing period can be reopened.");
            }
            var successful = closeMapper.selectLatestSuccessfulRunForGeneration(
                    context.organizationId(), period.id(), period.closeGeneration());
            if (successful == null) {
                throw conflict("The current CLOSED generation has no successful CloseRun.");
            }
            var now = clock.instant();
            var reopened = periods.reopen(context.organizationId(), period.id(), period.version(), now);
            audit.periodReopened(context.organizationId(), context.userId(), period.id(),
                    period.closeGeneration(), reopened.closeGeneration(), reasonCode, reasonNote);
            return new PeriodCloseView(reopened, successful,
                    closeMapper.selectChecksByRun(context.organizationId(), successful.id()));
        });
        if (result == null) {
            throw new IllegalStateException("Reopen transaction returned no result");
        }
        metrics.periodReopen("REOPENED");
        return result;
    }

    private List<CloseBlockerResult> evaluateAll(CloseBlockerContext context) {
        var results = new ArrayList<CloseBlockerResult>(CloseBlockerCode.values().length);
        for (var provider : blockers.providers()) {
            try {
                results.add(provider.evaluate(context));
            } catch (RuntimeException failure) {
                results.add(CloseBlockerResult.error(provider.code(), "BLOCKER_EVALUATION_ERROR"));
            }
        }
        validateSeven(results);
        return List.copyOf(results);
    }

    private PeriodCloseView finalizeClose(
            AuthorizationContext context, long closeRunId, List<CloseBlockerResult> results) {
        var result = transactions.execute(status -> {
            var runIdentity = closeMapper.selectRunByIdAndOrganization(
                    context.organizationId(), closeRunId);
            if (runIdentity == null) {
                throw notFound("Close run not found");
            }
            var period = periods.lockPeriod(context.organizationId(), runIdentity.billingPeriodId());
            var run = closeMapper.selectRunByIdForUpdate(context.organizationId(), closeRunId);
            if (run == null) {
                throw notFound("Close run not found");
            }

            if (run.status() != PeriodCloseRunStatus.CHECKING) {
                return terminalView(period, run);
            }
            if (period.status() != BillingPeriodStatus.CLOSING
                    || period.closeGeneration() != run.closeGeneration()) {
                throw conflict("Close finalization no longer matches the CLOSING period generation.");
            }

            validateSeven(results);
            var evaluatedAt = clock.instant();
            for (var check : results) {
                if (closeMapper.insertCheck(context.organizationId(), closeRunId,
                        check.code().name(), check.result().name(), check.itemCount(),
                        objectMapper.writeValueAsString(check.summary()), evaluatedAt, evaluatedAt) != 1) {
                    throw new IllegalStateException("Each CloseCheck insert must affect one row");
                }
            }

            var anyError = results.stream()
                    .anyMatch(check -> check.result() == PeriodCloseCheckResult.ERROR);
            var failedChecks = results.stream()
                    .filter(check -> check.result() == PeriodCloseCheckResult.FAIL)
                    .count();
            BillingPeriod terminalPeriod;
            if (anyError) {
                if (closeMapper.markRunFailed(context.organizationId(), closeRunId,
                        CLOSE_ERROR_CODE, "One or more Close blocker providers failed.",
                        evaluatedAt, evaluatedAt) != 1) {
                    throw conflict("CloseRun failure finalization lost its CHECKING state.");
                }
                terminalPeriod = periods.returnOpen(context.organizationId(), period.id(),
                        period.version(), evaluatedAt);
                audit.closeFailed(context.organizationId(), context.userId(), closeRunId,
                        period.id(), CLOSE_ERROR_CODE);
            } else if (failedChecks > 0) {
                if (closeMapper.markRunBlocked(context.organizationId(), closeRunId,
                        evaluatedAt, evaluatedAt) != 1) {
                    throw conflict("CloseRun blocked finalization lost its CHECKING state.");
                }
                terminalPeriod = periods.returnOpen(context.organizationId(), period.id(),
                        period.version(), evaluatedAt);
                audit.closeBlocked(context.organizationId(), context.userId(), closeRunId,
                        period.id(), failedChecks);
            } else {
                var latestRun = reconciliationMapper.selectLatestRunForPeriod(
                        context.organizationId(), period.id());
                if (latestRun == null || latestRun.status() != ReconciliationRunStatus.COMPLETED) {
                    throw new IllegalStateException(
                            "All Close blockers passed without a completed current reconciliation run");
                }
                if (closeMapper.markRunClosed(context.organizationId(), closeRunId,
                        latestRun.id(), evaluatedAt, evaluatedAt) != 1) {
                    throw conflict("CloseRun success finalization lost its CHECKING state.");
                }
                terminalPeriod = periods.markClosed(context.organizationId(), period.id(),
                        period.version(), evaluatedAt);
                audit.periodClosed(context.organizationId(), context.userId(), closeRunId,
                        period.id(), period.closeGeneration());
            }
            var terminalRun = closeMapper.selectRunByIdAndOrganization(
                    context.organizationId(), closeRunId);
            if (terminalRun == null) {
                throw new IllegalStateException("A finalized CloseRun must be readable");
            }
            return new PeriodCloseView(terminalPeriod, terminalRun,
                    closeMapper.selectChecksByRun(context.organizationId(), closeRunId));
        });
        if (result == null) {
            throw new IllegalStateException("Close finalize transaction returned no result");
        }
        return result;
    }

    private PeriodCloseView terminalView(BillingPeriod period, PeriodCloseRun run) {
        var valid = (run.status() == PeriodCloseRunStatus.CLOSED
                        && period.status() == BillingPeriodStatus.CLOSED)
                || ((run.status() == PeriodCloseRunStatus.BLOCKED
                        || run.status() == PeriodCloseRunStatus.FAILED)
                        && period.status() == BillingPeriodStatus.OPEN);
        if (!valid) {
            throw conflict("CloseRun and BillingPeriod terminal states are inconsistent.");
        }
        return view(period, run);
    }

    private PeriodCloseView view(BillingPeriod period, PeriodCloseRun run) {
        return new PeriodCloseView(period, run,
                closeMapper.selectChecksByRun(period.organizationId(), run.id()));
    }

    private static void validateSeven(List<CloseBlockerResult> results) {
        if (results.size() != CloseBlockerCode.values().length) {
            throw new IllegalStateException("A Close evaluation must contain exactly seven results");
        }
        Map<CloseBlockerCode, CloseBlockerResult> unique = results.stream().collect(
                Collectors.toMap(CloseBlockerResult::code, Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("Duplicate Close blocker result: " + left.code());
                        }));
        if (unique.size() != CloseBlockerCode.values().length) {
            throw new IllegalStateException("A Close evaluation must contain all canonical blocker codes");
        }
        for (var code : CloseBlockerCode.values()) {
            if (!unique.containsKey(code)) {
                throw new IllegalStateException("Missing Close blocker result: " + code);
            }
        }
    }

    private static String requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid reopen reason", field + " is required.");
        }
        var normalized = value.strip();
        if (normalized.length() > max) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid reopen reason", field + " must be at most " + max + " characters.");
        }
        return normalized;
    }

    private static DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Billing period Close conflict", detail);
    }

    private static DomainException notFound(String title) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                title, "The resource is not available in the current organization.");
    }

    record BeginResult(BillingPeriod period, PeriodCloseRun run, boolean alreadyTerminal) {
    }

    public record ReopenPeriodCommand(String reasonCode, String reasonNote) {
    }
}
