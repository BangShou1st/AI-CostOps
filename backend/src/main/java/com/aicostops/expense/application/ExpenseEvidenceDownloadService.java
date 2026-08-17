package com.aicostops.expense.application;

import com.aicostops.evidence.application.EvidenceDownloadService.EvidenceDownload;
import com.aicostops.evidence.application.EvidencePersistenceService;
import com.aicostops.evidence.application.ObjectStoragePort;
import com.aicostops.evidence.domain.Evidence;
import com.aicostops.evidence.domain.EvidenceStorageStatus;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Expense-scoped evidence download. Employees never get the org-wide
 * EVIDENCE_DOWNLOAD grant: they may only fetch their own expense's primary
 * evidence (EXPENSE_READ_OWN + owner); finance uses EXPENSE_REVIEW with no
 * owner comparison. Both paths require the evidence to be AVAILABLE.
 */
@Service
public class ExpenseEvidenceDownloadService {

    private static final String PERMISSION_EXPENSE_READ_OWN = "EXPENSE_READ_OWN";
    private static final String PERMISSION_EXPENSE_REVIEW = "EXPENSE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpenseClaimMapper expenseClaims;
    private final EvidencePersistenceService persistence;
    private final ObjectStoragePort storage;

    public ExpenseEvidenceDownloadService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper expenseClaims,
            EvidencePersistenceService persistence,
            ObjectStoragePort storage) {
        this.authorizationContexts = authorizationContexts;
        this.expenseClaims = expenseClaims;
        this.persistence = persistence;
        this.storage = storage;
    }

    /**
     * Routes by applicable grant: a reviewer with EXPENSE_REVIEW at ORG scope
     * may read any same-org expense's evidence; everyone else must satisfy the
     * OWN read + owner comparison.
     */
    public EvidenceDownload download(AuthenticatedUser user, long expenseId) {
        var context = authorizationContexts.current(user);
        if (hasOrgReviewGrant(context)) {
            return downloadForReview(context, expenseId);
        }
        return downloadOwn(context, expenseId);
    }

    private boolean hasOrgReviewGrant(AuthorizationContext context) {
        var scopes = M1AdminPermissionPolicy.applicableScopes(PERMISSION_EXPENSE_REVIEW);
        return context.grants().stream().anyMatch(grant ->
                grant.permissionCode().equals(PERMISSION_EXPENSE_REVIEW)
                        && scopes.contains(grant.scopeType())
                        && grant.scopeType() == ScopeType.ORG
                        && grant.scopeId() == context.organizationId());
    }

    /** Own expense evidence download. Non-owned or missing -> privacy 404. */
    public EvidenceDownload downloadOwn(AuthenticatedUser user, long expenseId) {
        var context = authorizationContexts.current(user);
        return downloadOwn(context, expenseId);
    }

    private EvidenceDownload downloadOwn(AuthorizationContext context, long expenseId) {
        authorization.requireOrg(context, PERMISSION_EXPENSE_READ_OWN);
        var claim = expenseClaims.selectByIdAndOrganization(context.organizationId(), expenseId);
        if (claim == null || !claim.isOwnedBy(context.organizationMemberId())) {
            throw notFound();
        }
        return openEvidence(context, claim.evidenceId());
    }

    /** Finance review evidence download (same org, no owner comparison). */
    public EvidenceDownload downloadForReview(AuthenticatedUser user, long expenseId) {
        var context = authorizationContexts.current(user);
        return downloadForReview(context, expenseId);
    }

    private EvidenceDownload downloadForReview(AuthorizationContext context, long expenseId) {
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        var claim = expenseClaims.selectByIdAndOrganization(context.organizationId(), expenseId);
        if (claim == null) {
            throw notFound();
        }
        return openEvidence(context, claim.evidenceId());
    }

    private EvidenceDownload openEvidence(AuthorizationContext context, Long evidenceId) {
        if (evidenceId == null) {
            throw notFound();
        }
        var evidence = persistence.findByIdAndOrganization(evidenceId, context.organizationId())
                .orElseThrow(ExpenseEvidenceDownloadService::notFound);
        if (evidence.storageStatus() != EvidenceStorageStatus.AVAILABLE) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Evidence is not available", "The evidence has not finished storing.");
        }
        try {
            return new EvidenceDownload(evidence, storage.open(evidence.objectKey()));
        } catch (com.aicostops.evidence.infrastructure.ObjectStorageException exception) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                    "Evidence storage unavailable",
                    "The evidence object store is temporarily unavailable.");
        }
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Expense evidence not found",
                "The expense or its evidence is not available to the current user.");
    }
}