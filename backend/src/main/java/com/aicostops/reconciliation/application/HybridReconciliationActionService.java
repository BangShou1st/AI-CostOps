package com.aicostops.reconciliation.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.ledger.application.LedgerCorrectionIdempotencyStore;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ChargeScopeContext;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.CorrectionEntrySource;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.DispositionInsert;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ReconciliationEvidenceRow;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.SourceScope;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evidence-item actions on a reconciliation case: charge posting dispositions
 * and correction linkage. These append immutable bounded evidence; they never
 * resolve sibling evidence and never mutate financial truth by themselves.
 *
 * <p>Both actions are bound to the case scope: a manual charge disposition is
 * only legal for a Charge whose confirmed import lineage belongs to the
 * case's provider account, currency and run BillingPeriod window with an
 * eligible review status, and a correction link is only legal when every
 * corrected Ledger entry resolves to the case's provider account and currency
 * through its preserved direct-source lineage.
 */
@Service
public class HybridReconciliationActionService {

    private static final String PERMISSION_RESOLVE = "RECONCILIATION_RESOLVE";
    private static final String DISPOSITION_OPERATION = "RECONCILIATION_CHARGE_DISPOSITION";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ReconciliationMapper mapper;
    private final HybridReconciliationMapper hybridMapper;
    private final LedgerCorrectionIdempotencyStore idempotency;
    private final ReconciliationAuditPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public HybridReconciliationActionService(
            AuthorizationContextService authorizationContexts,
            ReconciliationMapper mapper,
            HybridReconciliationMapper hybridMapper,
            LedgerCorrectionIdempotencyStore idempotency,
            ReconciliationAuditPort audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.hybridMapper = hybridMapper;
        this.idempotency = idempotency;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public long decideChargeDisposition(AuthenticatedUser user, long caseId,
            ChargeDispositionCommand command, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        validateDispositionCommand(command);
        var requestHash = sha256("operation=" + DISPOSITION_OPERATION
                + "\norgId=" + context.organizationId()
                + "\nactorMemberId=" + context.organizationMemberId()
                + "\ncaseId=" + caseId
                + "\nchargeFactId=" + command.chargeFactId()
                + "\ndisposition=" + command.disposition()
                + "\nreasonCode=" + command.reasonCode()
                + "\nreasonNote=" + command.reasonNote());
        var dispositionId = transactions.execute(status -> {
            var reservation = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), DISPOSITION_OPERATION, idempotencyKey,
                    requestHash);
            if (reservation.replay()) {
                return Long.parseLong(stripQuotes(reservation.responseBody()));
            }
            var currentCase = mapper.selectCaseByIdForUpdate(context.organizationId(), caseId);
            if (currentCase == null) {
                throw notFound("Reconciliation case");
            }
            validateChargeInCaseScope(context.organizationId(), command.chargeFactId(),
                    currentCase);
            // The Charge row is the shared financial-ownership serialization
            // point (always the last financial lock in the canonical order).
            hybridMapper.lockChargeForFinancialOwnership(context.organizationId(),
                    command.chargeFactId());
            assertChargeNotGatewayOwned(context.organizationId(), command.chargeFactId(),
                    currentCase.reconciliationRunId());
            // A Charge already posted through the normal V1 provider path is
            // the direct financial owner; it can never be reclassified as
            // RECONCILIATION_EVIDENCE afterwards. Recording the
            // legacy-compatible DIRECT claim on a posted Charge is still
            // allowed exactly once (V23 LEGACY_POSTED semantics).
            if ("RECONCILIATION_EVIDENCE".equals(command.disposition())
                    && hybridMapper.countPostedProviderChargePostings(context.organizationId(),
                            command.chargeFactId()) > 0) {
                throw conflict("The charge is already posted through the normal provider "
                        + "charge path and can never become RECONCILIATION_EVIDENCE.");
            }
            if (hybridMapper.countDisposition(context.organizationId(),
                    command.chargeFactId()) > 0) {
                throw conflict("The charge already has a final posting disposition.");
            }
            var now = clock.instant();
            hybridMapper.insertDisposition(new DispositionInsert(
                    context.organizationId(), command.chargeFactId(), command.disposition(),
                    "MANUAL", currentCase.reconciliationRunId(), caseId,
                    context.organizationMemberId(), command.reasonCode(), command.reasonNote(),
                    now));
            var createdDispositionId = hybridMapper.lastInsertId();
            audit.chargeDispositionDecided(context.organizationId(), context.userId(),
                    createdDispositionId, caseId, command.chargeFactId(), command.disposition());
            hybridMapper.insertEvidence(new ReconciliationEvidenceRow(
                    context.organizationId(), currentCase.reconciliationRunId(), caseId,
                    "DISPOSITION:CHARGE:" + command.chargeFactId(),
                    currentCase.providerAccountId(), currentCase.currency(),
                    "RESOLUTION_ACTION", null, command.chargeFactId(),
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null,
                    now));
            idempotency.finalize(reservation.id(), 200, Long.toString(createdDispositionId));
            return createdDispositionId;
        });
        return dispositionId;
    }

    /**
     * Under the Charge ownership lock: a Charge already consumed as Gateway
     * financial evidence (a committed gateway_financial_resolution, its
     * GATEWAY_REQUEST adjustment, or an incompatible binding in the run) can
     * only ever keep RECONCILIATION_EVIDENCE as its final state — it can never
     * be reclassified as DIRECT_PROVIDER_CHARGE.
     */
    private void assertChargeNotGatewayOwned(long organizationId, long chargeFactId,
            long runId) {
        var gatewayOwned = hybridMapper.countResolutionByStatementCharge(organizationId,
                chargeFactId) > 0
                || hybridMapper.countAdjustmentByStatementCharge(organizationId,
                        chargeFactId) > 0;
        if (gatewayOwned) {
            throw conflict("The charge is already consumed by a gateway financial "
                    + "resolution and can never become DIRECT_PROVIDER_CHARGE.");
        }
        if (hybridMapper.countConflictingManualBinding(organizationId, runId, chargeFactId,
                -1L) > 0) {
            throw conflict("The charge is already bound to another gateway request in this "
                    + "run and can never become DIRECT_PROVIDER_CHARGE.");
        }
    }

    /**
     * Proves that the Charge belongs to the real scope of the reconciliation
     * case through one bounded projection: the confirmed import lineage must
     * own the case's provider account and currency, the Charge period must sit
     * inside the run's BillingPeriod window, the batch must be CONFIRMED with
     * the Charge's own attempt as its confirmed attempt, and the review status
     * must be eligible external truth. Without this proof the posting fence
     * could be bypassed by writing a disposition under an unrelated case.
     */
    private void validateChargeInCaseScope(long organizationId, long chargeFactId,
            ReconciliationCase currentCase) {
        var charge = hybridMapper.selectChargeScopeContext(organizationId, chargeFactId,
                currentCase.reconciliationRunId());
        if (charge == null) {
            throw notFound("Charge");
        }
        if (charge.providerAccountId() != currentCase.providerAccountId()) {
            throw conflict("The charge provider account does not belong to the provider "
                    + "account scope of this reconciliation case.");
        }
        if (!charge.currency().equals(currentCase.currency())) {
            throw conflict("The charge currency does not match the currency scope of this "
                    + "reconciliation case.");
        }
        if (charge.periodStart() == null
                || charge.periodStart().isBefore(charge.runPeriodStart())
                || !charge.periodStart().isBefore(charge.runPeriodEnd())) {
            throw conflict("The charge period_start is outside the reconciliation run's "
                    + "BillingPeriod window.");
        }
        if (!"CONFIRMED".equals(charge.batchStatus()) || !charge.confirmedLineage()) {
            throw conflict("The charge is not part of a confirmed external import.");
        }
        if (!"CLEAN".equals(charge.reviewStatus())
                && !"SUSPECTED_DUPLICATE".equals(charge.reviewStatus())) {
            throw conflict("The charge review status is not eligible external truth.");
        }
    }

    public long linkCorrection(AuthenticatedUser user, long caseId,
            CorrectionLinkCommand command) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        if (command == null || command.correctionGroupId() <= 0) {
            throw validation("correctionGroupId must be a positive integer.");
        }
        return transactions.execute(status -> {
            var currentCase = mapper.selectCaseByIdForUpdate(context.organizationId(), caseId);
            if (currentCase == null) {
                throw notFound("Reconciliation case");
            }
            if (!hybridMapper.correctionGroupExists(context.organizationId(),
                    command.correctionGroupId())) {
                throw notFound("Correction group");
            }
            if (hybridMapper.countEvidenceKey(context.organizationId(),
                    currentCase.reconciliationRunId(),
                    "CORRECTION_LINK:" + command.correctionGroupId()) > 0) {
                throw conflict("The correction is already linked to this run.");
            }
            validateCorrectionLineage(context.organizationId(), command.correctionGroupId(),
                    currentCase);
            var now = clock.instant();
            hybridMapper.insertEvidence(new ReconciliationEvidenceRow(
                    context.organizationId(), currentCase.reconciliationRunId(), caseId,
                    "CORRECTION_LINK:" + command.correctionGroupId(),
                    currentCase.providerAccountId(), currentCase.currency(),
                    "RESOLUTION_ACTION", null,
                    null, null, null, null, null, command.correctionGroupId(),
                    null, null, null, null, null, null, null, null,
                    now));
            audit.correctionLinked(context.organizationId(), context.userId(), caseId,
                    command.correctionGroupId());
            return command.correctionGroupId();
        });
    }

    /**
     * Proves, through the preserved direct-source lineage of the corrected
     * Ledger entries, that the correction group belongs to the case's provider
     * account and currency. Mixed accounts, mixed currencies, entries without a
     * recognizable provider source and foreign scopes are all rejected; no
     * time/amount approximation is ever used.
     */
    private void validateCorrectionLineage(long organizationId, long correctionGroupId,
            ReconciliationCase currentCase) {
        var entries = hybridMapper.selectCorrectionEntrySources(organizationId,
                correctionGroupId);
        if (entries.isEmpty()) {
            throw conflict("The correction group has no Ledger entries to link.");
        }
        var scopes = new LinkedHashSet<SourceScope>();
        for (CorrectionEntrySource entry : entries) {
            var scope = resolveEntryProviderScope(organizationId, entry);
            if (scope == null) {
                throw conflict("A corrected Ledger entry has no recognizable provider "
                        + "financial source.");
            }
            scopes.add(scope);
        }
        if (scopes.size() != 1) {
            throw conflict("The correction group mixes multiple provider financial scopes.");
        }
        var scope = scopes.iterator().next();
        if (scope.providerAccountId() != currentCase.providerAccountId()
                || !scope.currency().equals(currentCase.currency())) {
            throw conflict("The correction group provider lineage does not match the "
                    + "provider account and currency scope of this reconciliation case.");
        }
    }

    private SourceScope resolveEntryProviderScope(long organizationId,
            CorrectionEntrySource entry) {
        var providerSources = 0;
        SourceScope resolved = null;
        if (entry.sourceChargeFactId() != null) {
            providerSources++;
            resolved = hybridMapper.selectChargeProviderScope(organizationId,
                    entry.sourceChargeFactId());
        }
        if (entry.sourceGatewaySettlementId() != null) {
            providerSources++;
            resolved = hybridMapper.selectGatewaySettlementScope(organizationId,
                    entry.sourceGatewaySettlementId());
        }
        if (entry.sourceReconciliationAdjustmentId() != null) {
            providerSources++;
            var adjustment = hybridMapper.selectAdjustmentByIdAndOrganization(organizationId,
                    entry.sourceReconciliationAdjustmentId());
            resolved = adjustment == null ? null
                    : new SourceScope(adjustment.providerAccountId(), adjustment.currency());
        }
        if (providerSources != 1) {
            // Exactly one provider direct source is required; a mixed or
            // provider-less entry can never prove case lineage.
            return null;
        }
        return resolved;
    }

    private static void validateDispositionCommand(ChargeDispositionCommand command) {
        if (command == null || command.chargeFactId() <= 0) {
            throw validation("chargeFactId must be a positive integer.");
        }
        if (!"RECONCILIATION_EVIDENCE".equals(command.disposition())
                && !"DIRECT_PROVIDER_CHARGE".equals(command.disposition())) {
            throw validation("disposition must be RECONCILIATION_EVIDENCE or "
                    + "DIRECT_PROVIDER_CHARGE; SYSTEM_EXACT decisions are only created by "
                    + "certified exact correlation.");
        }
        requireBounded(command.reasonCode(), 64, "reasonCode");
        requireBounded(command.reasonNote(), 2000, "reasonNote");
    }

    private static String stripQuotes(String responseBody) {
        var value = responseBody == null ? "" : responseBody.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 must be available", unavailable);
        }
    }

    private static void requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        if (value.strip().length() > max) {
            throw validation(field + " must be at most " + max + " characters.");
        }
    }

    private static DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Reconciliation evidence action conflict", detail);
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid reconciliation action", detail);
    }

    private static DomainException notFound(String type) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Resource not found", type + " is not available in the current organization.");
    }

    public record ChargeDispositionCommand(
            long chargeFactId,
            String disposition,
            String reasonCode,
            String reasonNote) {
    }

    public record CorrectionLinkCommand(long correctionGroupId) {
    }
}
