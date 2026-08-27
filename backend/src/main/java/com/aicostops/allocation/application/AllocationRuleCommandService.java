package com.aicostops.allocation.application;

import com.aicostops.allocation.application.AllocationCommands.RuleDefinitionCommand;
import com.aicostops.allocation.infrastructure.AllocationOrganizationLockMapper;
import com.aicostops.attribution.application.AllocationRuleRepository;
import com.aicostops.attribution.application.AllocationTargetDirectory;
import com.aicostops.attribution.application.NewAllocationRuleVersion;
import com.aicostops.attribution.domain.AllocationRule;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Optional;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Immutable rule version creation and lifecycle archiving.
 *
 * <p>Versions are server-authoritative: {@code maxVersion + 1} inside an
 * organization-row lock, so concurrent creation of the same rule key can
 * never produce a duplicate version number. Definition columns are never
 * updated; a new definition is always a new version.
 */
@Service
public class AllocationRuleCommandService {

    private static final String PERMISSION_ALLOCATION_RULE_MANAGE = "ALLOCATION_RULE_MANAGE";
    private static final int DEADLOCK_RETRIES = 3;

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final AllocationRuleRepository rules;
    private final AllocationTargetDirectory targets;
    private final AllocationOrganizationLockMapper organizationLocks;
    private final AllocationIdempotency idempotency;
    private final AllocationResponseCodec codec;
    private final AllocationAuditPort audit;
    private final TransactionTemplate transactions;

    public AllocationRuleCommandService(
            AuthorizationContextService authorizationContexts,
            AllocationRuleRepository rules,
            AllocationTargetDirectory targets,
            AllocationOrganizationLockMapper organizationLocks,
            AllocationIdempotency idempotency,
            AllocationResponseCodec codec,
            AllocationAuditPort audit,
            PlatformTransactionManager transactionManager) {
        this.authorizationContexts = authorizationContexts;
        this.rules = rules;
        this.targets = targets;
        this.organizationLocks = organizationLocks;
        this.idempotency = idempotency;
        this.codec = codec;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AllocationRule createVersion(AuthenticatedUser user, String ruleKey,
            RuleDefinitionCommand definition, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_RULE_MANAGE);
        AllocationIdempotency.validateKey(idempotencyKey);
        validateDefinitionShape(ruleKey, definition);
        var requestHash = idempotency.ruleVersionRequestHash(context.organizationId(),
                context.organizationMemberId(), ruleKey, definition);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var reserved = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), AllocationIdempotency.OPERATION_RULE_VERSION,
                    idempotencyKey, requestHash);
            if (reserved.replay()) {
                return codec.ruleFromJson(reserved.responseBody());
            }
            // Current-database-state checks run only for genuinely new
            // commands: a stored replay must not be re-validated against
            // targets or provider accounts that may have changed since.
            validateDefinitionTargets(context.organizationId(), definition);
            var organizationLocked = organizationLocks.lockOrganization(context.organizationId());
            if (organizationLocked == null) {
                throw new IllegalStateException(
                        "The organization row must exist for rule version creation");
            }
            if (rules.existsActiveOverlapSameKey(context.organizationId(), ruleKey,
                    definition.effectiveFrom(), definition.effectiveTo())) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Rule version range overlaps an ACTIVE version",
                        "The new version's effective range overlaps an ACTIVE version of "
                                + "the same rule key; archive it first or use an adjacent "
                                + "half-open range.");
            }
            var version = rules.maxVersion(context.organizationId(), ruleKey) + 1;
            var ruleId = rules.insertVersion(new NewAllocationRuleVersion(
                    context.organizationId(), ruleKey, version,
                    definition.name(), definition.providerCode(), definition.providerAccountId(),
                    definition.matchHintType(), definition.matchValue(), definition.priority(),
                    definition.targetProjectId(), definition.targetCostCenterId(),
                    definition.targetTeamId(), definition.effectiveFrom(), definition.effectiveTo(),
                    context.organizationMemberId()));
            var created = rules.findByIdAndOrganization(context.organizationId(), ruleId)
                    .orElseThrow(() -> new IllegalStateException(
                            "A just-written rule version must be readable"));
            idempotency.finalize(reserved.id(), 200, codec.ruleToJson(created));
            // Emit inside the same transaction: an audit write failure rolls
            // back both the published version and its idempotency record.
            audit.ruleVersionPublished(context.organizationId(), user.userId(),
                    created.id(), created.ruleKey(), created.version());
            return created;
        }));
    }

    public AllocationRule archive(AuthenticatedUser user, long ruleId, String idempotencyKey) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_ALLOCATION_RULE_MANAGE);
        AllocationIdempotency.validateKey(idempotencyKey);
        var requestHash = idempotency.ruleArchiveRequestHash(context.organizationId(),
                context.organizationMemberId(), ruleId);
        return executeWithDeadlockRetry(() -> transactions.execute(status -> {
            var reserved = idempotency.reserve(context.organizationId(),
                    context.organizationMemberId(), AllocationIdempotency.OPERATION_RULE_ARCHIVE,
                    idempotencyKey, requestHash);
            if (reserved.replay()) {
                return codec.ruleFromJson(reserved.responseBody());
            }
            var rule = rules.findByIdForUpdate(context.organizationId(), ruleId)
                    .orElseThrow(this::ruleNotFound);
            if (rule.status() == com.aicostops.attribution.domain.AllocationRuleStatus.ARCHIVED) {
                throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                        "Rule already archived",
                        "The rule version is already ARCHIVED; only ACTIVE rules can be archived.");
            }
            rules.archiveRule(context.organizationId(), ruleId);
            var archived = rules.findByIdAndOrganization(context.organizationId(), ruleId)
                    .orElseThrow(() -> new IllegalStateException(
                            "A just-archived rule version must be readable"));
            idempotency.finalize(reserved.id(), 200, codec.ruleToJson(archived));
            // Emit inside the same transaction: an audit write failure rolls
            // back the archive and its idempotency record.
            audit.ruleArchived(context.organizationId(), user.userId(),
                    archived.id(), archived.ruleKey(), archived.version());
            return archived;
        }));
    }

    /** Pure shape validation: no database state is consulted. */
    private static void validateDefinitionShape(String ruleKey, RuleDefinitionCommand definition) {
        if (ruleKey == null || ruleKey.isBlank() || ruleKey.length() > 100) {
            throw validation("ruleKey must be a nonblank value of at most 100 characters.");
        }
        if (definition.name() == null || definition.name().isBlank()
                || definition.name().length() > 200) {
            throw validation("name must be a nonblank value of at most 200 characters.");
        }
        if (definition.matchValue() == null || definition.matchValue().isBlank()
                || definition.matchValue().length() > 500) {
            throw validation("matchValue must be a nonblank value of at most 500 characters.");
        }
        if (definition.priority() < 1 || definition.priority() > 9999) {
            throw validation("priority must be between 1 and 9999.");
        }
        if (definition.effectiveTo() != null
                && !definition.effectiveFrom().isBefore(definition.effectiveTo())) {
            throw validation("effectiveFrom must be before effectiveTo.");
        }
        var targetCount = (definition.targetProjectId() == null ? 0 : 1)
                + (definition.targetCostCenterId() == null ? 0 : 1)
                + (definition.targetTeamId() == null ? 0 : 1);
        if (targetCount != 1) {
            throw validation("Exactly one target is required: projectId, costCenterId, or teamId.");
        }
    }

    /** Current-database-state validation for genuinely new commands only. */
    private void validateDefinitionTargets(long organizationId, RuleDefinitionCommand definition) {
        var targetActive = definition.targetProjectId() != null
                ? targets.activeProjectExists(organizationId, definition.targetProjectId())
                : definition.targetCostCenterId() != null
                        ? targets.activeCostCenterExists(organizationId, definition.targetCostCenterId())
                        : targets.activeTeamExists(organizationId, definition.targetTeamId());
        if (!targetActive) {
            throw validation("The target must be an ACTIVE project, cost center, or team "
                    + "of the current organization.");
        }
        if (definition.providerAccountId() != null
                && !targets.providerAccountExists(organizationId,
                        definition.providerAccountId(), definition.providerCode())) {
            throw validation("providerAccountId must belong to the current organization "
                    + "and match the provider code.");
        }
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid rule definition", detail);
    }

    private DomainException ruleNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Allocation rule not found",
                "The allocation rule is not available in the current organization.");
    }

    private <T> T executeWithDeadlockRetry(java.util.function.Supplier<T> operation) {
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
