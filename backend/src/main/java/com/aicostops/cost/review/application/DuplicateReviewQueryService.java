package com.aicostops.cost.review.application;

import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import com.aicostops.cost.review.domain.CandidateStatus;
import com.aicostops.cost.review.domain.CandidateType;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Duplicate candidate reads. Every call resolves the authorization context
 * first and requires DUPLICATE_REVIEW at ORG scope; candidates of other
 * organizations are invisible (privacy-preserving 404).
 */
@Service
public class DuplicateReviewQueryService {

    private static final String PERMISSION_DUPLICATE_REVIEW = "DUPLICATE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final DuplicateCandidateRepository candidates;

    public DuplicateReviewQueryService(
            AuthorizationContextService authorizationContexts,
            DuplicateCandidateRepository candidates) {
        this.authorizationContexts = authorizationContexts;
        this.candidates = candidates;
    }

    public PageResponse<CandidateSummary> list(
            AuthenticatedUser user,
            int page,
            int size,
            String status,
            String candidateType) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_DUPLICATE_REVIEW);
        var pageRequest = validPage(page, size);
        return candidates.page(context.organizationId(), pageRequest.page(), pageRequest.size(),
                parseStatus(status), parseType(candidateType));
    }

    public CandidateSummary get(AuthenticatedUser user, long candidateId) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_DUPLICATE_REVIEW);
        return candidates.findSummaryById(context.organizationId(), candidateId)
                .orElseThrow(DuplicateReviewQueryService::notFound);
    }

    private static CandidateStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return CandidateStatus.valueOf(status);
        } catch (IllegalArgumentException invalid) {
            throw filterRejected("status", status);
        }
    }

    private static CandidateType parseType(String candidateType) {
        if (candidateType == null || candidateType.isBlank()) {
            return null;
        }
        try {
            return CandidateType.valueOf(candidateType);
        } catch (IllegalArgumentException invalid) {
            throw filterRejected("candidateType", candidateType);
        }
    }

    private static DomainException filterRejected(String name, String value) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid filter value",
                "The " + name + " filter must be a valid enum value: " + value);
    }

    private static PageRequest validPage(int page, int size) {
        try {
            return PageRequest.of(page, size);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid page or size",
                    "Page must be zero or greater and size must be between 1 and 200.");
        }
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Duplicate candidate not found",
                "The duplicate candidate is not available in the current organization.");
    }
}
