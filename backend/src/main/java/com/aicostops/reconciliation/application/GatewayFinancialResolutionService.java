package com.aicostops.reconciliation.application;

import com.aicostops.budget.application.BillingPeriodFinancialWriteFence;
import com.aicostops.budget.application.CommitmentConsumeService;
import com.aicostops.budget.application.LedgerBudgetPort;
import com.aicostops.budget.application.LedgerBudgetPort.EntryScopeAmount;
import com.aicostops.budget.domain.BillingPeriodStatus;
import com.aicostops.budget.domain.Budget;
import com.aicostops.budget.domain.BudgetCommitment;
import com.aicostops.gatewaysettlement.application.GatewayFinancialTerminalPort;
import com.aicostops.gatewaysettlement.infrastructure.GatewayReservationSettlementMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.observability.AiCostOpsMetrics;
import com.aicostops.ledger.application.LedgerCorrectionIdempotencyStore;
import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort;
import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort.AdjustmentLineCommand;
import com.aicostops.ledger.application.ReconciliationAdjustmentLedgerPort.AdjustmentPostCommand;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.AdjustmentInsert;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.RequestResolutionLineage;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ResolutionInsert;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M15 gateway financial resolution: one immutable reviewed terminal financial
 * decision per Gateway request. It never competes with a normal M13
 * settlement path and never rewrites Gateway request, usage or Settlement
 * facts. Possible-billable attempts only; SAFE_NO_BILLABLE_EXECUTION is never
 * a resolution candidate.
 */
@Service
public class GatewayFinancialResolutionService {

    static final String OPERATION = "GATEWAY_FINANCIAL_RESOLUTION";
    private static final String PERMISSION_RESOLVE = "RECONCILIATION_RESOLVE";
    private static final String PERMISSION_LEDGER_CORRECT = "LEDGER_CORRECT";
    private static final String TYPE_STATEMENT = "STATEMENT_ADJUSTMENT_POSTED";
    private static final String TYPE_NO_CHARGE = "NO_CHARGE_CONFIRMED";
    private static final Set<String> POSSIBLE_BILLABLE =
            Set.of("DISPATCH_INTENT", "BILLABLE_POSSIBLE", "COMPLETED");

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodFinancialWriteFence periodFence;
    private final LedgerBudgetPort budgets;
    private final GatewayReservationSettlementMapper reservations;
    private final CommitmentConsumeService commitmentConsume;
    private final ReconciliationMapper mapper;
    private final HybridReconciliationMapper hybridMapper;
    private final ReconciliationAdjustmentLedgerPort adjustmentLedger;
    private final GatewayFinancialTerminalPort financialTerminal;
    private final LedgerCorrectionIdempotencyStore idempotency;
    private final ReconciliationAuditPort audit;
    private final AiCostOpsMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public GatewayFinancialResolutionService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodFinancialWriteFence periodFence,
            LedgerBudgetPort budgets,
            GatewayReservationSettlementMapper reservations,
            CommitmentConsumeService commitmentConsume,
            ReconciliationMapper mapper,
            HybridReconciliationMapper hybridMapper,
            ReconciliationAdjustmentLedgerPort adjustmentLedger,
            GatewayFinancialTerminalPort financialTerminal,
            LedgerCorrectionIdempotencyStore idempotency,
            ReconciliationAuditPort audit,
            AiCostOpsMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.periodFence = periodFence;
        this.budgets = budgets;
        this.reservations = reservations;
        this.commitmentConsume = commitmentConsume;
        this.mapper = mapper;
        this.hybridMapper = hybridMapper;
        this.adjustmentLedger = adjustmentLedger;
        this.financialTerminal = financialTerminal;
        this.idempotency = idempotency;
        this.audit = audit;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public GatewayResolutionResult resolveGatewayFinancialWork(
            AuthenticatedUser user, GatewayResolutionCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        authorization.requireOrg(context, PERMISSION_LEDGER_CORRECT);
        validateCommand(command);
        var requestHash = requestHash(context.organizationId(), context.organizationMemberId(),
                command);
        var result = transactions.execute(status -> {
            var reservation = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), OPERATION, idempotencyKey, requestHash);
            if (reservation.replay()) {
                return replay(context.organizationId(), reservation.responseBody());
            }
            return resolveInTransaction(context.organizationId(), context.userId(),
                    context.organizationMemberId(), command, reservation.id());
        });
        metrics.gatewayFinancialResolution(command.resolutionType(), "RESOLVED");
        return result;
    }

    private GatewayResolutionResult resolveInTransaction(long organizationId, long actorUserId,
            long actorMemberId, GatewayResolutionCommand command, long reservationId) {
        var run = mapper.selectRunByIdAndOrganization(organizationId, command.runId());
        if (run == null) {
            throw notFound("Reconciliation run");
        }
        if (run.status() != ReconciliationRunStatus.COMPLETED) {
            throw conflict("Gateway financial resolution requires a COMPLETED run.");
        }
        if (command.caseId() != null
                && mapper.selectCaseByIdAndOrganization(organizationId, command.caseId()) == null) {
            throw notFound("Reconciliation case");
        }

        var lineage = hybridMapper.selectRequestResolutionLineage(organizationId,
                command.requestId());
        if (lineage == null || lineage.billingPeriodId() == null) {
            throw notFound("Gateway request");
        }
        if (financialTerminal.hasTerminalResolution(organizationId, command.requestId())) {
            throw conflict("The gateway request already has a terminal financial resolution.");
        }

        // Financial lock order: BillingPeriod(s) -> Budget -> Commitment ->
        // Reservation -> reconciliation identity -> Gateway Request source row.
        var requestPeriodId = lineage.billingPeriodId();
        var requestPeriod = periodFence.lockById(organizationId, requestPeriodId);
        if (requestPeriod.status() == BillingPeriodStatus.CLOSING) {
            throw conflict("A CLOSING period cannot receive gateway financial resolution.");
        }
        long adjustmentPeriodId = command.correctionPeriodId() == null
                ? requestPeriodId
                : command.correctionPeriodId();
        if (requestPeriod.status() == BillingPeriodStatus.CLOSED
                && adjustmentPeriodId == requestPeriodId) {
            throw conflict("A CLOSED historical period never receives the adjustment; select "
                    + "an OPEN correction period or reopen explicitly.");
        }
        var lockedPeriodIds = adjustmentPeriodId == requestPeriodId
                ? List.of(requestPeriodId)
                : List.of(Math.min(requestPeriodId, adjustmentPeriodId),
                        Math.max(requestPeriodId, adjustmentPeriodId));
        for (var periodId : lockedPeriodIds) {
            periodFence.lockById(organizationId, periodId);
        }
        var adjustmentPeriod = periodFence.lockById(organizationId, adjustmentPeriodId);
        if (adjustmentPeriod.status() != BillingPeriodStatus.OPEN) {
            throw conflict("The correction period must be OPEN; current status is "
                    + adjustmentPeriod.status() + ".");
        }

        var selection = budgets.resolveSelections(organizationId, adjustmentPeriodId,
                List.of(new EntryScopeAmount(0,
                        ScopeType.valueOf(lineage.financialScopeType()),
                        lineage.financialScopeId(), lineage.currency())))
                .getFirst();
        var lockedBudgets = selection.budget() == null ? List.<Budget>of()
                : budgets.lockBudgets(organizationId, List.of(selection.budget().id()));
        var lockedCommitment = command.commitmentId() == null ? null
                : budgets.lockCommitments(organizationId, List.of(command.commitmentId()))
                        .getFirst();
        var lockedReservation = lineage.reservationId() == null ? null
                : reservations.selectByIdForUpdate(organizationId, lineage.reservationId());

        mapper.selectRunByIdForUpdate(organizationId, command.runId());
        if (command.caseId() != null) {
            mapper.selectCaseByIdForUpdate(organizationId, command.caseId());
        }

        // Source-row serialization point against late usage publication and
        // concurrent normal settlement.
        hybridMapper.lockGatewayRequest(organizationId, command.requestId());
        var current = hybridMapper.selectRequestResolutionLineage(organizationId,
                command.requestId());
        if (current == null) {
            throw conflict("The gateway request changed while resolving.");
        }
        validateEligibility(current);
        if (current.settlementId() != null
                && "RECONCILIATION_REQUIRED".equals(current.settlementStatus())
                && TYPE_NO_CHARGE.equals(command.resolutionType())) {
            throw conflict("A RECONCILIATION_REQUIRED Settlement contradicts a no-charge "
                    + "confirmation; correct the settlement instead.");
        }

        var now = clock.instant();
        Long adjustmentId = null;
        if (TYPE_STATEMENT.equals(command.resolutionType())) {
            adjustmentId = postRequestAdjustment(organizationId, actorMemberId, command,
                    run.id(), command.caseId(), current, selection.budget(), lockedBudgets.isEmpty()
                            ? null : lockedBudgets.getFirst(),
                    lockedCommitment, adjustmentPeriodId, requestPeriodId, reservationId, now);
        }

        var reservationOutcome = "NONE";
        if (lockedReservation != null
                && ("ACTIVE".equals(lockedReservation.status())
                        || "PENDING_HOLD".equals(lockedReservation.status()))) {
            var changed = TYPE_STATEMENT.equals(command.resolutionType())
                    ? reservations.finalizeForSettlement(organizationId, lockedReservation.id(),
                            lockedReservation.version(), now)
                    : reservations.releaseForReconciliation(organizationId, lockedReservation.id(),
                            lockedReservation.version(), now);
            if (changed != 1) {
                throw conflict("The bound reservation changed while resolving.");
            }
            reservationOutcome = TYPE_STATEMENT.equals(command.resolutionType())
                    ? "FINALIZED" : "RELEASED";
        }

        audit.gatewayFinancialResolved(organizationId, actorUserId, run.id(), command.caseId(),
                command.requestId(), command.resolutionType(), reservationOutcome,
                current.currency());
        var resolutionId = insertResolution(organizationId, run.id(), command, current,
                adjustmentId, lockedReservation == null ? null : lockedReservation.id(),
                reservationOutcome, actorMemberId, now);
        insertResolutionEvidence(organizationId, run.id(), command.caseId(), current,
                resolutionId, adjustmentId, now);
        idempotency.finalize(reservationId, 200, Long.toString(resolutionId));
        return new GatewayResolutionResult(resolutionId, adjustmentId, reservationOutcome);
    }

    private void validateEligibility(RequestResolutionLineage lineage) {
        if (lineage.routeAttemptId() == null
                || !POSSIBLE_BILLABLE.contains(lineage.attemptStatus())) {
            throw conflict("Only a possible-billable attempt is a resolution candidate; "
                    + "PLANNED and SAFE_NO_BILLABLE_EXECUTION never are.");
        }
        if (lineage.usageFactId() != null && "FINAL".equals(lineage.usageStatus())
                && lineage.settlementId() == null) {
            throw conflict("Ordinary FINAL usage without a Settlement belongs to the normal "
                    + "M13 settlement path; M15 resolution is not eligible.");
        }
        if (lineage.settlementId() != null) {
            var status = lineage.settlementStatus();
            if ("PENDING".equals(status) || "RETRYABLE_FAILED".equals(status)) {
                throw conflict("A " + status + " Settlement continues through the existing "
                        + "worker/retry semantics; M15 resolution is not eligible.");
            }
            if ("SETTLED".equals(status)) {
                throw conflict("A SETTLED Settlement is immutable financial truth; correct it "
                        + "with an append-only Ledger correction instead.");
            }
        }
    }

    private long postRequestAdjustment(long organizationId, long actorMemberId,
            GatewayResolutionCommand command, long runId, Long caseId,
            RequestResolutionLineage current, Budget budget, Budget lockedBudget,
            BudgetCommitment lockedCommitment, long adjustmentPeriodId,
            long requestPeriodId, long reservationId, Instant now) {
        var amount = command.adjustmentAmount();
        hybridMapper.insertAdjustment(new AdjustmentInsert(
                organizationId, runId, caseId, "ADJ:" + reservationId, "GATEWAY_REQUEST",
                current.providerAccountId(), current.currency(), amount, adjustmentPeriodId,
                current.requestId(), current.routeAttemptId(), actorMemberId,
                command.reasonCode(), command.reasonNote(), now));
        var adjustmentId = hybridMapper.lastInsertId();
        var scopeType = ScopeType.valueOf(current.financialScopeType());
        var posted = adjustmentLedger.postAdjustment(new AdjustmentPostCommand(
                organizationId, adjustmentId, adjustmentPeriodId,
                List.of(new AdjustmentLineCommand(0, amount, current.currency(),
                        scopeType == ScopeType.PROJECT ? current.financialScopeId() : null,
                        scopeType == ScopeType.COST_CENTER ? current.financialScopeId() : null,
                        scopeType == ScopeType.TEAM ? current.financialScopeId() : null,
                        budget == null ? null : budget.id())),
                actorMemberId, now));
        if (budget != null) {
            budgets.incrementActual(organizationId, budget.id(), amount, now);
        }
        if (lockedCommitment != null) {
            if (adjustmentPeriodId != requestPeriodId) {
                throw conflict("A cross-period resolution never consumes the historical "
                        + "commitment.");
            }
            if (amount.signum() <= 0) {
                throw conflict("Only a positive incurred adjustment may consume a commitment.");
            }
            if (lockedCommitment.budgetId() != (budget == null ? -1 : budget.id())
                    || !lockedCommitment.status().canConsume()) {
                throw conflict("The explicitly bound commitment is not consumable for the "
                        + "selected budget.");
            }
            commitmentConsume.consume(new CommitmentConsumeService.ConsumeCommand(
                    organizationId, lockedCommitment.id(), amount,
                    posted.entryIds().getFirst()));
        }
        return adjustmentId;
    }

    private long insertResolution(long organizationId, long runId,
            GatewayResolutionCommand command, RequestResolutionLineage current,
            Long adjustmentId, Long reservationId, String reservationOutcome,
            long actorMemberId, Instant now) {
        try {
            hybridMapper.insertResolution(new ResolutionInsert(
                    organizationId, runId, command.caseId(), current.requestId(),
                    current.routeAttemptId(), current.usageFactId(), current.settlementId(),
                    null, adjustmentId, reservationId, command.resolutionType(),
                    reservationOutcome, actorMemberId, command.reasonCode(),
                    command.reasonNote(), now));
            return hybridMapper.lastInsertId();
        } catch (DuplicateKeyException duplicate) {
            throw conflict("The gateway request already has a terminal financial resolution.");
        }
    }

    private void insertResolutionEvidence(long organizationId, long runId, Long caseId,
            RequestResolutionLineage current, long resolutionId, Long adjustmentId,
            Instant now) {
        hybridMapper.insertEvidence(
                new HybridReconciliationMapper.ReconciliationEvidenceRow(
                        organizationId, runId, caseId,
                        "RESOLUTION:REQUEST:" + current.requestId(),
                        current.providerAccountId(), current.currency(), "RESOLUTION_ACTION",
                        null, null, current.requestId(), current.routeAttemptId(),
                        current.usageFactId(), current.settlementId(), null,
                        adjustmentId, resolutionId, null, null, null, null, null, now));
    }

    private GatewayResolutionResult replay(long organizationId, String responseBody) {
        long resolutionId;
        try {
            var value = responseBody == null ? "" : responseBody.trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            resolutionId = Long.parseLong(value);
        } catch (RuntimeException invalidStoredResponse) {
            throw new IllegalStateException("Stored resolution idempotency response is invalid",
                    invalidStoredResponse);
        }
        return new GatewayResolutionResult(resolutionId, null, null);
    }

    private String requestHash(long organizationId, long actorMemberId,
            GatewayResolutionCommand command) {
        var canonical = "operation=" + OPERATION
                + "\norgId=" + organizationId
                + "\nactorMemberId=" + actorMemberId
                + "\nrunId=" + command.runId()
                + "\ncaseId=" + command.caseId()
                + "\nrequestId=" + command.requestId()
                + "\nresolutionType=" + command.resolutionType()
                + "\nadjustmentAmount="
                + (command.adjustmentAmount() == null ? ""
                        : command.adjustmentAmount().toPlainString())
                + "\ncorrectionPeriodId=" + command.correctionPeriodId()
                + "\ncommitmentId=" + command.commitmentId()
                + "\nreasonCode=" + command.reasonCode()
                + "\nreasonNote=" + command.reasonNote();
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }

    private static void validateCommand(GatewayResolutionCommand command) {
        if (command == null || command.runId() <= 0 || command.requestId() <= 0) {
            throw validation("runId and requestId must be positive integers.");
        }
        if (!TYPE_STATEMENT.equals(command.resolutionType())
                && !TYPE_NO_CHARGE.equals(command.resolutionType())) {
            throw validation("resolutionType must be STATEMENT_ADJUSTMENT_POSTED or "
                    + "NO_CHARGE_CONFIRMED.");
        }
        if (command.reasonCode() == null || command.reasonCode().isBlank()
                || command.reasonCode().length() > 64) {
            throw validation("reasonCode must be a nonblank value of at most 64 characters.");
        }
        if (command.reasonNote() == null || command.reasonNote().isBlank()
                || command.reasonNote().length() > 2000) {
            throw validation("reasonNote must contain the reviewed evidence summary.");
        }
        if (TYPE_STATEMENT.equals(command.resolutionType())) {
            var amount = command.adjustmentAmount();
            if (amount == null || amount.signum() == 0) {
                throw validation("A statement-backed resolution requires a nonzero reviewed "
                        + "amount.");
            }
            if (amount.scale() > 8 || amount.precision() - amount.scale() > 12) {
                throw validation("adjustmentAmount must fit DECIMAL(20,8).");
            }
        } else if (command.adjustmentAmount() != null) {
            throw validation("A no-charge confirmation never posts an adjustment amount.");
        }
    }

    private static DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Gateway financial resolution conflict", detail);
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid gateway financial resolution", detail);
    }

    private static DomainException notFound(String type) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", type + " is not available in the current organization.");
    }

    public record GatewayResolutionCommand(
            long runId,
            Long caseId,
            long requestId,
            String resolutionType,
            BigDecimal adjustmentAmount,
            Long correctionPeriodId,
            Long commitmentId,
            String reasonCode,
            String reasonNote) {
    }

    public record GatewayResolutionResult(
            long resolutionId,
            Long adjustmentId,
            String reservationOutcome) {
    }
}
