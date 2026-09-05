package com.aicostops.reconciliation.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper;
import com.aicostops.reconciliation.infrastructure.HybridReconciliationMapper.EvidenceRow;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Read projections over M15 hybrid reconciliation evidence. */
@Service
public class HybridReconciliationQueryService {

    private static final String PERMISSION_READ = "RECONCILIATION_READ";
    private static final int MAX_PAGE_SIZE = 200;

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
            int page, int size) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_READ);
        reconciliationQueries.getRun(user, runId);
        var boundedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        var boundedPage = Math.max(0, page);
        var items = mapper.selectEvidenceByRun(context.organizationId(), runId,
                boundedSize, boundedPage * boundedSize);
        var total = mapper.countEvidenceByRun(context.organizationId(), runId);
        return toPage(items, total, boundedPage, boundedSize);
    }

    public PageResponse<EvidenceRow> listCaseEvidence(AuthenticatedUser user, long caseId,
            int page, int size) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_READ);
        reconciliationQueries.getCase(user, caseId);
        var boundedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        var boundedPage = Math.max(0, page);
        var items = mapper.selectEvidenceByCase(context.organizationId(), caseId,
                boundedSize, boundedPage * boundedSize);
        var total = mapper.countEvidenceByCase(context.organizationId(), caseId);
        return toPage(items, total, boundedPage, boundedSize);
    }

    private static PageResponse<EvidenceRow> toPage(java.util.List<EvidenceRow> items,
            long total, int page, int size) {
        var totalPages = size == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }
}
