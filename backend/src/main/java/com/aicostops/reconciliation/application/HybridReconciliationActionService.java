package com.aicostops.reconciliation.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.ledger.application.LedgerCorrectionIdempotencyStore;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.DispositionInsert;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.ReconciliationEvidenceRow;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evidence-item actions on a reconciliation case: charge posting dispositions
 * and correction linkage. These append immutable bounded evidence; they never
 * resolve sibling evidence and never mutate financial truth by themselves.
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
            if (!hybridMapper.chargeExists(context.organizationId(), command.chargeFactId())) {
                throw notFound("Charge");
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
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    now));
            idempotency.finalize(reservation.id(), 200, Long.toString(createdDispositionId));
            return createdDispositionId;
        });
        return dispositionId;
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
            var now = clock.instant();
            hybridMapper.insertEvidence(new ReconciliationEvidenceRow(
                    context.organizationId(), currentCase.reconciliationRunId(), caseId,
                    "CORRECTION_LINK:" + command.correctionGroupId(),
                    currentCase.providerAccountId(), currentCase.currency(),
                    "RESOLUTION_ACTION", null, null, null, null, null, null,
                    command.correctionGroupId(), null, null, null, null, null, null, null,
                    now));
            audit.correctionLinked(context.organizationId(), context.userId(), caseId,
                    command.correctionGroupId());
            return command.correctionGroupId();
        });
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
