package com.aicostops.reconciliation.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.EvidenceRow;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read projections over M15 hybrid reconciliation evidence. */
@Service
public class HybridReconciliationQueryService {

    private static final String PERMISSION_READ = "RECONCILIATION_READ";
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Bounded evidence vocabulary for the optional matchKind filter. The
     * filter is an enum check, never an arbitrary SQL-like predicate.
     */
    static final Set<String> MATCH_KIND_VOCABULARY = Set.of(
            "EXACT_PROVIDER_REQUEST",
            "AGGREGATE_SCOPE",
            "GATEWAY_UNRESOLVED",
            "MANUAL_BINDING",
            "RESOLUTION_ACTION");

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final HybridReconciliationMapper mapper;
    private final ReconciliationQueryService reconciliationQueries;

    public HybridReconciliationQueryService(
            AuthorizationContextService authorizationContexts,
            HybridReconciliationMapper mapper,
            ReconciliationQueryService reconciliationQueries) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.reconciliationQueries = reconciliationQueries;
    }

    public PageResponse<EvidenceRow> listRunEvidence(AuthenticatedUser user, long runId,
            int page, int size, String matchKind, Long gatewayRequestId) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_READ);
        reconciliationQueries.getRun(user, runId);
        var boundedKind = requireBoundedMatchKind(matchKind);
        var boundedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        var boundedPage = Math.max(0, page);
        var items = mapper.selectEvidenceByRun(context.organizationId(), runId, boundedKind,
                gatewayRequestId, boundedSize, boundedPage * boundedSize);
        var total = mapper.countEvidenceByRun(context.organizationId(), runId, boundedKind,
                gatewayRequestId);
        return toPage(items, total, boundedPage, boundedSize);
    }

    public PageResponse<EvidenceRow> listCaseEvidence(AuthenticatedUser user, long caseId,
            int page, int size, String matchKind) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_READ);
        reconciliationQueries.getCase(user, caseId);
        var boundedKind = requireBoundedMatchKind(matchKind);
        var boundedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        var boundedPage = Math.max(0, page);
        var items = mapper.selectEvidenceByCase(context.organizationId(), caseId, boundedKind,
                boundedSize, boundedPage * boundedSize);
        var total = mapper.countEvidenceByCase(context.organizationId(), caseId, boundedKind);
        return toPage(items, total, boundedPage, boundedSize);
    }

    private static String requireBoundedMatchKind(String matchKind) {
        if (matchKind == null) {
            return null;
        }
        if (!MATCH_KIND_VOCABULARY.contains(matchKind)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid evidence filter",
                    "matchKind must be one of the bounded evidence kinds: "
                            + MATCH_KIND_VOCABULARY + ".");
        }
        return matchKind;
    }

    private static PageResponse<EvidenceRow> toPage(java.util.List<EvidenceRow> items,
            long total, int page, int size) {
        var totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }
}
