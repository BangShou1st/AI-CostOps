package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.domain.ExpenseClaim;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.evidence.application.EvidenceStorageService;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Employee primary-evidence upload. The evidence bytes are staged and stored
 * through the existing Evidence flow first (never inside a long expense
 * transaction), then a short transaction locks the expense, rechecks
 * owner/state/expected-version, and attaches the evidence id with an optimistic
 * version increment. SUBMITTED/APPROVED expenses cannot swap evidence.
 */
@Service
public class ExpenseEvidenceUploadService {

    private static final int DEADLOCK_RETRIES = 3;
    private static final String PERMISSION_EVIDENCE_UPLOAD_OWN = "EVIDENCE_UPLOAD_OWN";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final EvidenceStorageService evidenceStorage;
    private final ExpenseClaimMapper mapper;
    private final ExpenseAuditPort audit;
    private final ExpenseResponseCodec responseCodec;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ExpenseEvidenceUploadService(
            AuthorizationContextService authorizationContexts,
            EvidenceStorageService evidenceStorage,
            ExpenseClaimMapper mapper,
            ExpenseAuditPort audit,
            ExpenseResponseCodec responseCodec,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.evidenceStorage = evidenceStorage;
        this.mapper = mapper;
        this.audit = audit;
        this.responseCodec = responseCodec;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Stages and stores the evidence, then attaches it to the expense with an
     * expectedVersion CAS. Reuses an identical {@code (org, sha256)} evidence
     * row when the same file is uploaded again.
     */
    public ExpenseDetail attach(AuthenticatedUser user, long expenseId, long expectedVersion,
            String originalFilename, String mediaType, InputStream content) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EVIDENCE_UPLOAD_OWN);

        var claim = mapper.selectByIdAndOrganization(context.organizationId(), expenseId);
        requireOwnedEditable(context, claim);

        // Evidence storage runs outside the expense lock and its own short
        // transactions; the attach below rechecks the expense state.
        var stored = evidenceStorage.store(context.organizationId(),
                context.organizationMemberId(), originalFilename, mediaType, content);

        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var locked = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            requireOwnedEditable(context, locked);
            if (locked.version() != expectedVersion) {
                throw staleVersion();
            }
            if (mapper.attachEvidence(context.organizationId(), expenseId, expectedVersion,
                    stored.evidence().id(), clock.instant()) != 1) {
                throw staleVersion();
            }
            var updated = mapper.selectByIdForUpdate(context.organizationId(), expenseId);
            var detail = toDetail(context.organizationId(), updated);
            audit.evidenceAttached(context.organizationId(), context.userId(), detail.id(),
                    detail.evidenceId(), detail.version());
            return detail;
        }));
    }

    private void requireOwnedEditable(AuthorizationContext context, ExpenseClaim claim) {
        if (claim == null || !claim.isOwnedBy(context.organizationMemberId())) {
            throw notFound();
        }
        if (!claim.status().editableByOwner()) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Expense is not editable",
                    "Evidence can only be attached to a DRAFT or NEEDS_INFO expense.");
        }
    }

    private ExpenseDetail toDetail(long organizationId, ExpenseClaim claim) {
        var approvalStatus = claim.approvalCaseId() == null ? null
                : mapper.selectApprovalCaseByExpense(organizationId, claim.id()).status();
        var decisionConfirmed = claim.currentAllocationDecisionId() == null ? false
                : "CONFIRMED".equals(mapper.selectDecisionStatus(
                        organizationId, claim.currentAllocationDecisionId()));
        var history = claim.approvalCaseId() == null ? List.<com.aicostops.expense.domain.ApprovalAction>of()
                : mapper.selectApprovalActionsByCase(organizationId, claim.approvalCaseId());
        return ExpenseDetail.from(claim, approvalStatus, decisionConfirmed, history);
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Expense not found",
                "The expense is not available to the current user.");
    }

    private static DomainException staleVersion() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Expense version conflict",
                "The expense was modified by another request; reload and retry.");
    }

    private <T> T executeWithDeadlockRetry(Supplier<T> operation) {
        for (var attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (DeadlockLoserDataAccessException deadlock) {
                if (attempt >= DEADLOCK_RETRIES) {
                    throw deadlock;
                }
            }
        }
    }
}