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
import com.aicostops.reconciliation.application.ProviderCorrelationProfileRegistry.CorrelationField;
import com.aicostops.reconciliation.domain.ReconciliationRunStatus;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.AdjustmentInsert;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ChargeScopeContext;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.EvidenceRow;
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
import java.util.TreeMap;
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
 *
 * <p>Financial safety rules enforced here:
 * <ul>
 * <li>the resolution is bound to one COMPLETED run whose BillingPeriod owns
 *     the request; an optional case must belong to that run and to the
 *     request's provider account/currency scope;</li>
 * <li>STATEMENT_ADJUSTMENT_POSTED amounts are server-derived from one
 *     strongly-bound authoritative statement Charge minus the immutable
 *     Ledger amount attributable to the same request; the client never
 *     defines a Ledger amount;</li>
 * <li>the statement Charge is bound either by certified exact correlation
 *     evidence of the same run or by an explicit reviewed MANUAL_BINDING that
 *     is validated and persisted in the same transaction;</li>
 * <li>NO_CHARGE_CONFIRMED requires positive reviewed proof: a bounded
 *     positive-proof reason code plus a persisted auditable evidence
 *     reference; statement absence alone never proves zero cost;</li>
 * <li>a Commitment is only ever consumed through the locked reservation's own
 *     commitment lineage, never by client selection;</li>
 * <li>the financial lock order is BillingPeriod(s) ascending id, Budget(s)
 *     ascending id, reservation-bound Commitment, bound Reservation,
 *     reconciliation run/case, Gateway Request source row, then mutation.</li>
 * </ul>
 */
@Service
public class GatewayFinancialResolutionService {

    static final String OPERATION = "GATEWAY_FINANCIAL_RESOLUTION";
    private static final String PERMISSION_RESOLVE = "RECONCILIATION_RESOLVE";
    private static final String PERMISSION_LEDGER_CORRECT = "LEDGER_CORRECT";
    static final String TYPE_STATEMENT = "STATEMENT_ADJUSTMENT_POSTED";
    static final String TYPE_NO_CHARGE = "NO_CHARGE_CONFIRMED";
    private static final Set<String> POSSIBLE_BILLABLE =
            Set.of("DISPATCH_INTENT", "BILLABLE_POSSIBLE", "COMPLETED");

    /** Bounded positive-proof vocabulary for NO_CHARGE_CONFIRMED. */
    static final Set<String> NO_CHARGE_PROOF_CODES = Set.of(
            "PROVIDER_PORTAL_CONFIRMED_NO_CHARGE",
            "PROVIDER_SUPPORT_CONFIRMED_NO_CHARGE",
            "EXPLICIT_ZERO_PROVIDER_RECORD");
    /**
     * Binding classification is server-derived truth (exact correlation
     * evidence vs reviewed manual binding); a client may never declare it.
     * The client reason code is only a bounded business explanation.
     */
    static final Set<String> FORBIDDEN_CLIENT_CLASSIFICATION_CODES = Set.of(
            "EXACT_PROVIDER_REQUEST", "MANUAL_BINDING");

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
    private final ProviderCorrelationProfileRegistry correlationProfiles;
    private final LedgerCorrectionIdempotencyStore idempotency;
    private final ReconciliationAuditPort audit;
    private final GatewayResolutionFailureInjector failureInjector;
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
            ProviderCorrelationProfileRegistry correlationProfiles,
            LedgerCorrectionIdempotencyStore idempotency,
            ReconciliationAuditPort audit,
            GatewayResolutionFailureInjector failureInjector,
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
        this.correlationProfiles = correlationProfiles;
        this.idempotency = idempotency;
        this.audit = audit;
        this.failureInjector = failureInjector;
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
        // Pre-read immutable identity/context without financial locks.
        var run = mapper.selectRunByIdAndOrganization(organizationId, command.runId());
        if (run == null) {
            throw notFound("Reconciliation run");
        }
        if (run.status() != ReconciliationRunStatus.COMPLETED) {
            throw conflict("Gateway financial resolution requires a COMPLETED run.");
        }
        var preRead = hybridMapper.selectRequestResolutionLineage(organizationId,
                command.requestId());
        if (preRead == null || preRead.billingPeriodId() == null) {
            throw notFound("Gateway request");
        }
        if (preRead.billingPeriodId() != run.billingPeriodId()) {
            throw conflict("The gateway request belongs to billing period "
                    + preRead.billingPeriodId() + " while the run reviews billing period "
                    + run.billingPeriodId() + "; a resolution can never cross runs/periods.");
        }
        if (command.caseId() != null) {
            var caseRow = mapper.selectCaseByIdAndOrganization(organizationId, command.caseId());
            if (caseRow == null) {
                throw notFound("Reconciliation case");
            }
            if (caseRow.reconciliationRunId() != command.runId()) {
                throw conflict("The reconciliation case belongs to a different run of this "
                        + "organization.");
            }
            if (caseRow.providerAccountId() != preRead.providerAccountId()) {
                throw conflict("The reconciliation case provider account does not match the "
                        + "gateway request provider account.");
            }
            if (!caseRow.currency().equals(preRead.currency())) {
                throw conflict("The reconciliation case currency does not match the gateway "
                        + "request financial currency.");
            }
        }
        if (financialTerminal.hasTerminalResolution(organizationId, command.requestId())) {
            throw conflict("The gateway request already has a terminal financial resolution.");
        }

        // Statement binding is decided before financial locks and revalidated
        // after them.
        Long boundChargeId = null;
        StatementBinding binding = null;
        if (TYPE_STATEMENT.equals(command.resolutionType())) {
            binding = bindStatementCharge(organizationId, command, run.id(), preRead);
            boundChargeId = binding.chargeFactId();
        }

        // Financial lock order: all BillingPeriod rows strictly ascending id in
        // one pass, then Budget(s), the reservation-bound Commitment, the bound
        // Reservation, the reconciliation identity and the request source row.
        var requestPeriodId = preRead.billingPeriodId();
        var adjustmentPeriodId = command.correctionPeriodId() == null
                ? requestPeriodId
                : command.correctionPeriodId();
        var lockedPeriods = new TreeMap<Long, com.aicostops.budget.domain.BillingPeriod>();
        for (var periodId : java.util.stream.Stream
                .of(requestPeriodId, adjustmentPeriodId)
                .distinct()
                .sorted()
                .toList()) {
            lockedPeriods.put(periodId, periodFence.lockById(organizationId, periodId));
        }
        var requestPeriod = lockedPeriods.get(requestPeriodId);
        var adjustmentPeriod = lockedPeriods.get(adjustmentPeriodId);
        if (requestPeriod.status() == BillingPeriodStatus.CLOSING) {
            throw conflict("A CLOSING period cannot receive gateway financial resolution.");
        }
        if (requestPeriod.status() == BillingPeriodStatus.CLOSED
                && adjustmentPeriodId == requestPeriodId) {
            throw conflict("A CLOSED historical period never receives the adjustment; select "
                    + "an OPEN correction period or reopen explicitly.");
        }
        if (adjustmentPeriod.status() != BillingPeriodStatus.OPEN) {
            throw conflict("The correction period must be OPEN; current status is "
                    + adjustmentPeriod.status() + ".");
        }

        Budget budget = null;
        if (TYPE_STATEMENT.equals(command.resolutionType())) {
            var selection = budgets.resolveSelections(organizationId, adjustmentPeriodId,
                    List.of(new EntryScopeAmount(0,
                            ScopeType.valueOf(preRead.financialScopeType()),
                            preRead.financialScopeId(), preRead.currency())))
                    .getFirst();
            budget = selection.budget();
            if (budget != null) {
                budgets.lockBudgets(organizationId, List.of(budget.id()));
            }
        }

        // The Commitment is only ever bound through the reservation lineage.
        BudgetCommitment lockedCommitment = null;
        if (TYPE_STATEMENT.equals(command.resolutionType())
                && preRead.reservationCommitmentId() != null) {
            lockedCommitment = budgets.lockCommitments(organizationId,
                    List.of(preRead.reservationCommitmentId())).getFirst();
        }
        var lockedReservation = preRead.reservationId() == null ? null
                : reservations.selectByIdForUpdate(organizationId, preRead.reservationId());
        if (lockedCommitment != null) {
            if (lockedReservation == null || lockedReservation.commitmentId() == null
                    || lockedReservation.commitmentId() != lockedCommitment.id()) {
                throw conflict("The locked commitment is not the commitment of the bound "
                        + "reservation.");
            }
        }

        // Reconciliation identity locks and stale-basis revalidation.
        var lockedRun = mapper.selectRunByIdForUpdate(organizationId, command.runId());
        if (lockedRun == null || lockedRun.status() != ReconciliationRunStatus.COMPLETED) {
            throw conflict("The reconciliation run changed while resolving.");
        }
        if (command.caseId() != null
                && mapper.selectCaseByIdForUpdate(organizationId, command.caseId()) == null) {
            throw conflict("The reconciliation case changed while resolving.");
        }

        // Source-row serialization point against late usage publication and
        // concurrent normal settlement.
        hybridMapper.lockGatewayRequest(organizationId, command.requestId());
        var current = hybridMapper.selectRequestResolutionLineage(organizationId,
                command.requestId());
        if (current == null
                || current.billingPeriodId() == null
                || current.billingPeriodId() != requestPeriodId
                || current.providerAccountId() != preRead.providerAccountId()
                || !current.currency().equals(preRead.currency())) {
            throw conflict("The gateway request changed while resolving.");
        }
        validateEligibility(current);
        if (current.settlementId() != null
                && "RECONCILIATION_REQUIRED".equals(current.settlementStatus())
                && TYPE_NO_CHARGE.equals(command.resolutionType())) {
            throw conflict("A RECONCILIATION_REQUIRED Settlement contradicts a no-charge "
                    + "confirmation; correct the settlement instead.");
        }
        requireCurrentRunUnresolvedEvidence(organizationId, run.id(), command, current);

        var now = clock.instant();
        Long adjustmentId = null;
        if (TYPE_STATEMENT.equals(command.resolutionType())) {
            // Financial ownership: the Charge row is the shared serialization
            // point with normal Provider posting (charge row lock last in the
            // canonical order). Under the lock every ownership fact is
            // revalidated, and the RECONCILIATION_EVIDENCE claim is written
            // atomically with the adjustment so the Charge can never later be
            // posted through the normal V1 path.
            hybridMapper.lockChargeForFinancialOwnership(organizationId, boundChargeId);
            var claimRequired = revalidateChargeBinding(organizationId, run.id(),
                    command.requestId(), boundChargeId, current, binding.manual());
            if (claimRequired) {
                try {
                    hybridMapper.insertOwnershipDisposition(
                            new HybridReconciliationMapper.OwnershipDispositionInsert(
                                    organizationId, boundChargeId, binding.manual() ? "MANUAL"
                                            : "SYSTEM_EXACT",
                                    run.id(), command.caseId(),
                                    binding.manual() ? actorMemberId : null,
                                    command.reasonCode(), command.reasonNote(), now));
                } catch (DuplicateKeyException concurrentClaim) {
                    // The unique (org, charge) disposition constraint is the
                    // last line of ownership defense; the loser receives a
                    // bounded conflict instead of a raw constraint error.
                    throw conflict("The statement charge ownership was concurrently claimed "
                            + "by another gateway financial resolution.");
                }
                failureInjector.after("CHARGE_DISPOSITION_INSERTED");
            }
            adjustmentId = postRequestAdjustment(organizationId, actorMemberId, command,
                    run.id(), command.caseId(), current, budget, lockedCommitment, boundChargeId,
                    adjustmentPeriodId, requestPeriodId, reservationId, now);
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
        failureInjector.after("RESERVATION_TRANSITIONED");

        audit.gatewayFinancialResolved(organizationId, actorUserId, run.id(), command.caseId(),
                command.requestId(), command.resolutionType(), reservationOutcome,
                current.currency());
        failureInjector.after("AUDIT_WRITTEN");

        var resolutionId = insertResolution(organizationId, run.id(), command, current,
                boundChargeId, adjustmentId,
                lockedReservation == null ? null : lockedReservation.id(),
                reservationOutcome, actorMemberId, now);
        failureInjector.after("RESOLUTION_INSERTED");
        insertManualBindingEvidence(organizationId, run.id(), command, current, resolutionId,
                now);
        insertResolutionEvidence(organizationId, run.id(), command, current, resolutionId,
                adjustmentId, now);
        failureInjector.after("RESOLUTION_EVIDENCE_INSERTED");
        idempotency.finalize(reservationId, 200, Long.toString(resolutionId));
        return new GatewayResolutionResult(resolutionId, run.id(), command.caseId(),
                command.requestId(), command.resolutionType(), reservationOutcome, adjustmentId);
    }

    private record StatementBinding(long chargeFactId, boolean manual) {
    }

    /**
     * Decides the strongly-bound statement Charge for a statement-backed
     * resolution: certified exact correlation evidence of the same run, or an
     * explicit reviewer-selected Charge that is validated against the confirmed
     * external truth and the request scope (provider account, currency,
     * BillingPeriod window, unused by another resolution).
     */
    private StatementBinding bindStatementCharge(long organizationId,
            GatewayResolutionCommand command, long runId, RequestResolutionLineage lineage) {
        var exactRows = hybridMapper.selectExactEvidenceRowsForRequest(organizationId, runId,
                command.requestId());
        if (exactRows.size() > 1) {
            throw conflict("The run holds ambiguous exact correlation evidence for the "
                    + "request; automatic binding fails closed.");
        }
        if (exactRows.size() == 1) {
            var exact = exactRows.getFirst();
            if (exact.chargeFactId() == null
                    || exact.providerAccountId() != lineage.providerAccountId()
                    || !exact.currency().equals(lineage.currency())
                    || exact.gatewayRouteAttemptId() == null
                    || exact.gatewayRouteAttemptId() != lineage.routeAttemptId()) {
                throw conflict("The exact correlation evidence no longer matches the current "
                        + "request lineage.");
            }
            var profile = hybridMapper.selectChargeImportProfile(organizationId,
                    exact.chargeFactId());
            if (correlationProfiles.providerRecordKeySemantics(
                    profile == null ? null : profile.providerCode(),
                    profile == null ? null : profile.sourceType(),
                    profile == null ? null : profile.parserVersion())
                    != CorrelationField.PROVIDER_REQUEST_ID) {
                throw conflict("The bound charge import profile does not certify a provider "
                        + "request id.");
            }
            if (command.statementChargeFactId() != null
                    && command.statementChargeFactId() != exact.chargeFactId()) {
                throw conflict("The reviewed statement charge does not match the existing "
                        + "exact correlation binding of the request.");
            }
            return new StatementBinding(exact.chargeFactId(), false);
        }
        if (command.statementChargeFactId() == null) {
            throw validation("A statement-backed resolution requires a bound statement "
                    + "charge: exact correlation evidence or an explicitly reviewed "
                    + "statementChargeFactId.");
        }
        var charge = hybridMapper.selectChargeScopeContext(organizationId,
                command.statementChargeFactId(), runId);
        validateChargeInRunScope(charge, lineage);
        return new StatementBinding(command.statementChargeFactId(), true);
    }

    private void validateChargeInRunScope(ChargeScopeContext charge,
            RequestResolutionLineage lineage) {
        if (charge == null) {
            throw notFound("Statement charge");
        }
        if (charge.providerAccountId() != lineage.providerAccountId()) {
            throw conflict("The statement charge provider account does not match the gateway "
                    + "request provider account.");
        }
        if (!charge.currency().equals(lineage.currency())) {
            throw conflict("The statement charge currency does not match the gateway request "
                    + "financial currency.");
        }
        if (charge.periodStart() == null
                || charge.periodStart().isBefore(charge.runPeriodStart())
                || !charge.periodStart().isBefore(charge.runPeriodEnd())) {
            throw conflict("The statement charge period_start is outside the run's "
                    + "BillingPeriod window.");
        }
        if (!"CONFIRMED".equals(charge.batchStatus()) || !charge.confirmedLineage()) {
            throw conflict("The statement charge is not part of a confirmed external "
                    + "import.");
        }
        if (!"CLEAN".equals(charge.reviewStatus())
                && !"SUSPECTED_DUPLICATE".equals(charge.reviewStatus())) {
            throw conflict("The statement charge review status is not eligible external "
                    + "truth.");
        }
    }

    /**
     * Revalidates, under the Charge financial-ownership lock, that the charge
     * binding is still exclusive and that no financial representation of this
     * Charge already exists. Returns true when this transaction must write the
     * RECONCILIATION_EVIDENCE ownership disposition (a compatible claim written
     * earlier in the same run is reused, never duplicated).
     */
    private boolean revalidateChargeBinding(long organizationId, long runId, long requestId,
            long chargeFactId, RequestResolutionLineage current, boolean manualBinding) {
        // A charge already posted through the normal V1 provider path owns its
        // financial representation: reconciling it again would double-count.
        if (hybridMapper.countPostedProviderChargePostings(organizationId, chargeFactId) > 0) {
            throw conflict("The statement charge is already posted through the normal "
                    + "provider charge path and can never be consumed as reconciliation "
                    + "evidence.");
        }
        var disposition = hybridMapper.selectOwnershipDisposition(organizationId, chargeFactId);
        boolean claimRequired;
        if (disposition == null) {
            claimRequired = true;
        } else if ("DIRECT_PROVIDER_CHARGE".equals(disposition.disposition())) {
            throw conflict("The statement charge carries a DIRECT_PROVIDER_CHARGE "
                    + "disposition and can never be consumed as reconciliation evidence.");
        } else if (disposition.reconciliationRunId() == null
                || disposition.reconciliationRunId() != runId) {
            throw conflict("The statement charge is already classified as reconciliation "
                    + "evidence by a different reconciliation run; rerun reconciliation "
                    + "before resolving.");
        } else if ((!"MANUAL".equals(disposition.decisionSource())) == manualBinding) {
            throw conflict("The statement charge ownership decision source is incompatible "
                    + "with this binding; rerun reconciliation.");
        } else {
            claimRequired = false;
        }
        if (hybridMapper.countAdjustmentByStatementCharge(organizationId, chargeFactId) > 0) {
            throw conflict("The statement charge is already consumed by a gateway request "
                    + "adjustment.");
        }
        if (hybridMapper.countResolutionByStatementCharge(organizationId, chargeFactId) > 0) {
            throw conflict("The statement charge is already used by another gateway financial "
                    + "resolution.");
        }
        if (hybridMapper.countConflictingManualBinding(organizationId, runId, chargeFactId,
                requestId) > 0) {
            throw conflict("The statement charge is already bound to another request in this "
                    + "run.");
        }
        var charge = hybridMapper.selectChargeScopeContext(organizationId, chargeFactId, runId);
        validateChargeInRunScope(charge, current);
        return claimRequired;
    }

    private void insertManualBindingEvidence(long organizationId, long runId,
            GatewayResolutionCommand command, RequestResolutionLineage current, Long resolutionId,
            Instant now) {
        if (!TYPE_STATEMENT.equals(command.resolutionType())) {
            return;
        }
        var exactRows = hybridMapper.selectExactEvidenceRowsForRequest(organizationId, runId,
                command.requestId());
        if (!exactRows.isEmpty()) {
            return;
        }
        hybridMapper.insertEvidence(new HybridReconciliationMapper.ReconciliationEvidenceRow(
                organizationId, runId, command.caseId(),
                "MANUAL_BINDING:CHARGE:" + command.statementChargeFactId()
                        + ":REQUEST:" + command.requestId(),
                current.providerAccountId(), current.currency(), "MANUAL_BINDING", null,
                command.statementChargeFactId(), current.requestId(), current.routeAttemptId(),
                current.usageFactId(), current.settlementId(), null, null, resolutionId, null,
                null, null, null, null, null, now));
    }

    /**
     * A resolution may only be grounded in reviewed evidence of this run whose
     * route attempt, provider account and currency still equal the request's
     * current lineage. Evidence bound to an older attempt (e.g. after a M14
     * failover) or another scope is stale and requires a reconciliation rerun.
     */
    private void requireCurrentRunUnresolvedEvidence(long organizationId, long runId,
            GatewayResolutionCommand command, RequestResolutionLineage current) {
        var bindings = hybridMapper.selectUnresolvedEvidenceBindingsForRequest(
                organizationId, runId, command.requestId());
        if (bindings.isEmpty()) {
            throw conflict((TYPE_NO_CHARGE.equals(command.resolutionType())
                    ? "NO_CHARGE_CONFIRMED" : "STATEMENT_ADJUSTMENT_POSTED")
                    + " requires GATEWAY_UNRESOLVED evidence for the request in the "
                    + "reviewed run; a request reviewed by no reconciliation run can "
                    + "never be resolved here.");
        }
        var currentBinding = bindings.stream().anyMatch(binding ->
                binding.gatewayRouteAttemptId() != null
                        && binding.gatewayRouteAttemptId() == current.routeAttemptId()
                        && binding.providerAccountId() == current.providerAccountId()
                        && binding.currency().equals(current.currency()));
        if (!currentBinding) {
            throw conflict("The reviewed run evidence for this request is stale: it is "
                    + "bound to a different route attempt or financial scope than the "
                    + "current lineage. Rerun reconciliation before resolving.");
        }
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
            RequestResolutionLineage current, Budget budget, BudgetCommitment lockedCommitment,
            long statementChargeFactId, long adjustmentPeriodId, long requestPeriodId,
            long reservationId, Instant now) {
        // Server-derived amount: authoritative external statement charge minus
        // the immutable Ledger amount attributable to the same request. The
        // client never supplies a Ledger amount and the aggregate case
        // difference is never used.
        var externalAmount = hybridMapper.selectStatementChargeAmount(organizationId,
                statementChargeFactId);
        var internalAmount = hybridMapper.selectRequestPostedInternalAmount(organizationId,
                current.requestId());
        var amount = ReconciliationMoney.requireScale8Exact(
                externalAmount.subtract(internalAmount));
        if (amount.signum() == 0) {
            throw conflict("The bound statement charge is already fully represented by the "
                    + "posted internal truth of the request; no adjustment is derived.");
        }
        hybridMapper.insertAdjustment(new AdjustmentInsert(
                organizationId, runId, caseId, "ADJ:" + reservationId, "GATEWAY_REQUEST",
                current.providerAccountId(), current.currency(), amount, adjustmentPeriodId,
                current.requestId(), current.routeAttemptId(), statementChargeFactId,
                actorMemberId, command.reasonCode(), command.reasonNote(), now));
        var adjustmentId = hybridMapper.lastInsertId();
        failureInjector.after("ADJUSTMENT_INSERTED");
        var scopeType = ScopeType.valueOf(current.financialScopeType());
        var posted = adjustmentLedger.postAdjustment(new AdjustmentPostCommand(
                organizationId, adjustmentId, adjustmentPeriodId,
                List.of(new AdjustmentLineCommand(0, amount, current.currency(),
                        scopeType == ScopeType.PROJECT ? current.financialScopeId() : null,
                        scopeType == ScopeType.COST_CENTER ? current.financialScopeId() : null,
                        scopeType == ScopeType.TEAM ? current.financialScopeId() : null,
                        budget == null ? null : budget.id())),
                actorMemberId, now));
        failureInjector.after("LEDGER_ENTRY_INSERTED");
        if (budget != null) {
            budgets.incrementActual(organizationId, budget.id(), amount, now);
        }
        failureInjector.after("BUDGET_ACTUAL_MUTATED");
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
                throw conflict("The reservation-bound commitment is not consumable for the "
                        + "selected budget.");
            }
            commitmentConsume.consume(new CommitmentConsumeService.ConsumeCommand(
                    organizationId, lockedCommitment.id(), amount,
                    posted.entryIds().getFirst()));
            failureInjector.after("COMMITMENT_CONSUMED");
        }
        return adjustmentId;
    }

    private long insertResolution(long organizationId, long runId,
            GatewayResolutionCommand command, RequestResolutionLineage current,
            Long statementChargeFactId, Long adjustmentId, Long reservationId,
            String reservationOutcome, long actorMemberId, Instant now) {
        try {
            hybridMapper.insertResolution(new ResolutionInsert(
                    organizationId, runId, command.caseId(), current.requestId(),
                    current.routeAttemptId(), current.usageFactId(), current.settlementId(),
                    statementChargeFactId, adjustmentId, reservationId, command.resolutionType(),
                    reservationOutcome, actorMemberId, command.reasonCode(),
                    command.reasonNote(), now));
            return hybridMapper.lastInsertId();
        } catch (DuplicateKeyException duplicate) {
            throw conflict("The gateway request already has a terminal financial resolution.");
        }
    }

    private void insertResolutionEvidence(long organizationId, long runId,
            GatewayResolutionCommand command, RequestResolutionLineage current,
            long resolutionId, Long adjustmentId, Instant now) {
        hybridMapper.insertEvidence(
                new HybridReconciliationMapper.ReconciliationEvidenceRow(
                        organizationId, runId, command.caseId(),
                        "RESOLUTION:REQUEST:" + current.requestId(),
                        current.providerAccountId(), current.currency(), "RESOLUTION_ACTION",
                        null, null, current.requestId(), current.routeAttemptId(),
                        current.usageFactId(), current.settlementId(), null,
                        adjustmentId, resolutionId, null, null,
                        TYPE_NO_CHARGE.equals(command.resolutionType())
                                ? command.positiveEvidenceReference() : null,
                        null, null, null, now));
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
        var committed = hybridMapper.selectResolutionByIdAndOrganization(organizationId,
                resolutionId);
        if (committed == null) {
            throw new IllegalStateException("A committed resolution must be readable");
        }
        // Same key + same canonical request replays the committed business
        // response, not a degraded echo of it.
        return new GatewayResolutionResult(committed.id(), committed.reconciliationRunId(),
                committed.reconciliationCaseId(), committed.requestId(),
                committed.resolutionType(), committed.reservationOutcome(),
                committed.reconciliationAdjustmentId());
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
                + "\nstatementChargeFactId=" + command.statementChargeFactId()
                + "\npositiveEvidenceReference=" + command.positiveEvidenceReference()
                + "\ncorrectionPeriodId=" + command.correctionPeriodId()
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
        if (FORBIDDEN_CLIENT_CLASSIFICATION_CODES.contains(command.reasonCode())) {
            throw validation("reasonCode is a business explanation; the binding "
                    + "classification (EXACT_PROVIDER_REQUEST / MANUAL_BINDING) is derived "
                    + "by the server from the run evidence and can never be declared by "
                    + "the client.");
        }
        if (TYPE_STATEMENT.equals(command.resolutionType())) {
            if (command.statementChargeFactId() != null
                    && command.statementChargeFactId() <= 0) {
                throw validation("statementChargeFactId must be a positive integer when "
                        + "supplied.");
            }
            if (command.positiveEvidenceReference() != null) {
                throw validation("A statement-backed resolution never carries a positive "
                        + "no-charge evidence reference.");
            }
        } else {
            if (command.statementChargeFactId() != null) {
                throw validation("A no-charge confirmation never binds a statement charge.");
            }
            if (!NO_CHARGE_PROOF_CODES.contains(command.reasonCode())) {
                throw validation("reasonCode must be one of " + NO_CHARGE_PROOF_CODES
                        + "; statement absence alone never proves zero cost.");
            }
            var reference = command.positiveEvidenceReference();
            if (reference == null || reference.isBlank() || reference.strip().length() < 6
                    || reference.strip().length() > 256) {
                throw validation("positiveEvidenceReference must be a bounded auditable "
                        + "reference (6-256 characters) to the reviewed positive proof.");
            }
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
            Long statementChargeFactId,
            String positiveEvidenceReference,
            Long correctionPeriodId,
            String reasonCode,
            String reasonNote) {
    }

    public record GatewayResolutionResult(
            long resolutionId,
            long runId,
            Long caseId,
            long requestId,
            String resolutionType,
            String reservationOutcome,
            Long adjustmentId) {
    }
}
