package com.aicostops.reconciliation.application;

import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.reconciliation.domain.ReconciliationCase;
import com.aicostops.reconciliation.domain.ReconciliationCaseStatus;
import com.aicostops.reconciliation.infrastructure.ReconciliationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ReconciliationCaseService {

    private static final String PERMISSION_RESOLVE = "RECONCILIATION_RESOLVE";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final ReconciliationMapper mapper;
    private final ReconciliationAuditPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReconciliationCaseService(
            AuthorizationContextService authorizationContexts,
            ReconciliationMapper mapper,
            ReconciliationAuditPort audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ReconciliationCase investigate(AuthenticatedUser user, long caseId) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        return requireResult(transactions.execute(status -> {
            var current = requireCase(context.organizationId(), caseId);
            requireStatus(current, ReconciliationCaseStatus.OPEN,
                    "Only an OPEN reconciliation case can be investigated.");
            var now = clock.instant();
            if (mapper.markInvestigating(context.organizationId(), caseId, now) != 1) {
                throw conflict("Reconciliation case changed concurrently");
            }
            audit.caseTransition(context.organizationId(), context.userId(), caseId,
                    "INVESTIGATING", null);
            return mapper.selectCaseByIdAndOrganization(context.organizationId(), caseId);
        }));
    }

    public ReconciliationCase returnOpen(AuthenticatedUser user, long caseId) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        return requireResult(transactions.execute(status -> {
            var current = requireCase(context.organizationId(), caseId);
            requireStatus(current, ReconciliationCaseStatus.INVESTIGATING,
                    "Only an INVESTIGATING reconciliation case can return to OPEN.");
            var now = clock.instant();
            if (mapper.returnInvestigatingToOpen(context.organizationId(), caseId, now) != 1) {
                throw conflict("Reconciliation case changed concurrently");
            }
            audit.caseTransition(context.organizationId(), context.userId(), caseId,
                    "RETURNED_OPEN", null);
            return mapper.selectCaseByIdAndOrganization(context.organizationId(), caseId);
        }));
    }

    public ReconciliationCase resolve(
            AuthenticatedUser user, long caseId, ResolveCaseCommand command) {
        var context = authorizationContexts.fresh(user);
        authorization.requireOrg(context, PERMISSION_RESOLVE);
        var reasonCode = requireBounded(command == null ? null : command.reasonCode(),
                100, "reasonCode");
        var resolutionNote = requireBounded(command == null ? null : command.resolutionNote(),
                2000, "resolutionNote");
        return requireResult(transactions.execute(status -> {
            var current = requireCase(context.organizationId(), caseId);
            requireStatus(current, ReconciliationCaseStatus.INVESTIGATING,
                    "Only an INVESTIGATING reconciliation case can be resolved.");
            var now = clock.instant();
            if (mapper.markResolved(context.organizationId(), caseId, reasonCode, resolutionNote,
                    context.organizationMemberId(), now, now) != 1) {
                throw conflict("Reconciliation case changed concurrently");
            }
            audit.caseTransition(context.organizationId(), context.userId(), caseId,
                    "RESOLVED", reasonCode);
            return mapper.selectCaseByIdAndOrganization(context.organizationId(), caseId);
        }));
    }

    private ReconciliationCase requireCase(long organizationId, long caseId) {
        var result = mapper.selectCaseByIdForUpdate(organizationId, caseId);
        if (result == null) {
            throw new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                    "Reconciliation case not found",
                    "The reconciliation case is not available in the current organization.");
        }
        return result;
    }

    private static void requireStatus(ReconciliationCase current,
            ReconciliationCaseStatus expected, String detail) {
        if (current.status() != expected) {
            throw conflict(detail);
        }
    }

    private static String requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid reconciliation resolution", field + " is required.");
        }
        var normalized = value.strip();
        if (normalized.length() > max) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invalid reconciliation resolution",
                    field + " must be at most " + max + " characters.");
        }
        return normalized;
    }

    private static DomainException conflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Reconciliation case state conflict", detail);
    }

    private static <T> T requireResult(T result) {
        if (result == null) {
            throw new IllegalStateException("Reconciliation case transaction returned no result");
        }
        return result;
    }

    public record ResolveCaseCommand(String reasonCode, String resolutionNote) {
    }
}
