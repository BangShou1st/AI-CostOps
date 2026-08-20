package com.aicostops.ledger.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.iam.domain.M1AdminPermissionPolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.ledger.application.LedgerReadModels.LedgerEntryView;
import com.aicostops.ledger.application.LedgerReadModels.LedgerPostingView;
import com.aicostops.ledger.domain.LedgerSourceType;
import com.aicostops.ledger.infrastructure.LedgerQueryMapper;
import com.aicostops.ledger.infrastructure.LedgerQueryMapper.VisibilityScope;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.PageRequest;
import com.aicostops.shared.web.PageResponse;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Scoped, read-only Ledger projections and source lineage. */
@Service
public class LedgerQueryService {

    private static final String PERMISSION_LEDGER_READ = "LEDGER_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final LedgerQueryMapper mapper;

    public LedgerQueryService(AuthorizationContextService authorizationContexts,
            LedgerQueryMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public PageResponse<LedgerPostingView> listPostings(AuthenticatedUser user, PageRequest page,
            Long billingPeriodId, LedgerSourceType sourceType, Long projectId, Long costCenterId,
            Long teamId, String sort) {
        var context = authorizationContexts.current(user);
        var visibility = visibility(context);
        if (!visibility.organizationWide() && visibility.scopes().isEmpty()) {
            return PageResponse.of(List.of(), page, 0);
        }
        var order = parseSort(sort);
        var sourceName = sourceType == null ? null : sourceType.name();
        var total = mapper.countPostings(context.organizationId(), billingPeriodId, sourceName,
                projectId, costCenterId, teamId, visibility.organizationWide(), visibility.scopes());
        var postings = mapper.selectPostingPage(context.organizationId(), billingPeriodId, sourceName,
                projectId, costCenterId, teamId, visibility.organizationWide(), visibility.scopes(),
                order.column(), order.direction(), page.size(), offset(page));
        var views = postings.stream().map(posting -> new LedgerPostingView(posting,
                mapper.selectEntriesForPosting(context.organizationId(), posting.id(),
                        visibility.organizationWide(), visibility.scopes(), projectId, costCenterId,
                        teamId))).toList();
        return PageResponse.of(views, page, total);
    }

    public LedgerPostingView getPosting(AuthenticatedUser user, long postingId) {
        var context = authorizationContexts.current(user);
        var visibility = visibility(context);
        if (!visibility.organizationWide() && visibility.scopes().isEmpty()) {
            throw notFound();
        }
        var posting = mapper.selectPostingVisible(context.organizationId(), postingId,
                visibility.organizationWide(), visibility.scopes());
        if (posting == null) {
            throw notFound();
        }
        return new LedgerPostingView(posting, mapper.selectEntriesForPosting(
                context.organizationId(), postingId, visibility.organizationWide(), visibility.scopes(),
                null, null, null));
    }

    public PageResponse<com.aicostops.ledger.domain.LedgerEntry> listEntries(
            AuthenticatedUser user, PageRequest page, Long billingPeriodId,
            LedgerSourceType sourceType, Long projectId, Long costCenterId, Long teamId,
            String sort) {
        var context = authorizationContexts.current(user);
        var visibility = visibility(context);
        if (!visibility.organizationWide() && visibility.scopes().isEmpty()) {
            return PageResponse.of(List.of(), page, 0);
        }
        var order = parseSort(sort);
        var sourceName = sourceType == null ? null : sourceType.name();
        var total = mapper.countEntries(context.organizationId(), billingPeriodId, sourceName,
                projectId, costCenterId, teamId, visibility.organizationWide(), visibility.scopes());
        var entries = mapper.selectEntryPage(context.organizationId(), billingPeriodId, sourceName,
                projectId, costCenterId, teamId, visibility.organizationWide(), visibility.scopes(),
                order.entryColumn(), order.direction(), page.size(), offset(page));
        return PageResponse.of(entries, page, total);
    }

    public LedgerEntryView getEntry(AuthenticatedUser user, long entryId) {
        var context = authorizationContexts.current(user);
        var visibility = visibility(context);
        if (!visibility.organizationWide() && visibility.scopes().isEmpty()) {
            throw notFound();
        }
        var entry = mapper.selectEntryById(context.organizationId(), entryId);
        if (entry == null || !isVisible(entry, visibility)) {
            throw notFound();
        }
        var posting = mapper.selectPostingVisible(context.organizationId(), entry.postingId(),
                visibility.organizationWide(), visibility.scopes());
        if (posting == null) {
            throw notFound();
        }
        var visibleEntries = mapper.selectEntriesForPosting(context.organizationId(), posting.id(),
                visibility.organizationWide(), visibility.scopes(), null, null, null);
        return new LedgerEntryView(posting, entry,
                mapper.selectLineage(context.organizationId(), entryId), visibleEntries);
    }

    private Visibility visibility(com.aicostops.iam.domain.AuthorizationContext context) {
        var grants = context.grants().stream()
                .filter(grant -> grant.permissionCode().equals(PERMISSION_LEDGER_READ))
                .filter(grant -> M1AdminPermissionPolicy.applicableScopes(PERMISSION_LEDGER_READ)
                        .contains(grant.scopeType()))
                .toList();
        if (grants.isEmpty()) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Permission is required",
                    "The required permission is not granted at an applicable scope.");
        }
        var orgWide = grants.stream().anyMatch(grant -> grant.scopeType() == ScopeType.ORG
                && grant.scopeId() == context.organizationId());
        var scopes = grants.stream().filter(grant -> grant.scopeType() != ScopeType.ORG)
                .map(grant -> new VisibilityScope(grant.scopeType().name(), grant.scopeId()))
                .distinct().toList();
        return new Visibility(orgWide, scopes);
    }

    private static boolean isVisible(com.aicostops.ledger.domain.LedgerEntry entry,
            Visibility visibility) {
        if (visibility.organizationWide()) {
            return true;
        }
        return visibility.scopes().stream().anyMatch(scope ->
                (scope.scopeType().equals("PROJECT") && Objects.equals(scope.scopeId(), entry.projectId()))
                        || (scope.scopeType().equals("TEAM") && Objects.equals(scope.scopeId(), entry.teamId()))
                        || (scope.scopeType().equals("COST_CENTER")
                                && Objects.equals(scope.scopeId(), entry.costCenterId())));
    }

    private static SortOrder parseSort(String value) {
        var normalized = value == null || value.isBlank() ? "postedAt,desc"
                : value.trim();
        var parts = normalized.split(",", -1);
        if (parts.length != 2 || !parts[0].equals("postedAt")
                || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid Ledger sort", "sort must be postedAt,asc or postedAt,desc.");
        }
        var direction = parts[1].toUpperCase(Locale.ROOT);
        return new SortOrder("lp.posted_at", "lp.posted_at", direction);
    }

    private static int offset(PageRequest page) {
        return Math.multiplyExact(page.page(), page.size());
    }

    private static DomainException notFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Ledger resource not found", "The resource is not available at the granted scope.");
    }

    private record Visibility(boolean organizationWide, List<VisibilityScope> scopes) {
    }

    private record SortOrder(String column, String entryColumn, String direction) {
    }
}
