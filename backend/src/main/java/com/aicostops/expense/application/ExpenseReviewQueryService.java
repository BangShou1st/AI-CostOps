package com.aicostops.expense.application;

import com.aicostops.expense.application.ExpenseReadModels.ExpenseDetail;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Finance review reads: the org review queue (SUBMITTED / NEEDS_INFO /
 * APPROVED-unallocated, filterable) and the reviewer detail view. All require
 * EXPENSE_REVIEW at ORG scope; no owner comparison applies.
 */
@Service
public class ExpenseReviewQueryService {

    private static final String PERMISSION_EXPENSE_REVIEW = "EXPENSE_REVIEW";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ExpenseClaimMapper mapper;
    private final ExpenseQueryService expenseQueries;

    public ExpenseReviewQueryService(
            AuthorizationContextService authorizationContexts,
            ExpenseClaimMapper mapper,
            ExpenseQueryService expenseQueries) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.expenseQueries = expenseQueries;
    }

    public List<ExpenseDetail> listQueue(AuthenticatedUser user, String status, int page, int size) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        var filter = normalizeStatusFilter(status);
        var offset = Math.max(page, 0) * Math.max(size, 1);
        return mapper.selectReviewQueue(context.organizationId(), filter,
                        Math.max(size, 1), offset).stream()
                .map(claim -> expenseQueries.toDetail(context.organizationId(), claim))
                .toList();
    }

    public long countQueue(AuthenticatedUser user, String status) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_EXPENSE_REVIEW);
        return mapper.countReviewQueue(context.organizationId(), normalizeStatusFilter(status));
    }

    public ExpenseDetail getForReview(AuthenticatedUser user, long expenseId) {
        return expenseQueries.getForReview(user, expenseId);
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return "ALL";
        }
        var normalized = status.toUpperCase(Locale.ROOT);
        if (normalized.equals("SUBMITTED") || normalized.equals("NEEDS_INFO")
                || normalized.equals("APPROVED")) {
            return normalized;
        }
        return "ALL";
    }
}