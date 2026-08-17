package com.aicostops.allocation.api;

import com.aicostops.allocation.application.AllocationCommands.RuleDefinitionCommand;
import com.aicostops.attribution.domain.AllocationRuleMatchType;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Instant;
import org.springframework.http.HttpStatus;

/**
 * Rule version request body plus HTTP-bound validation. The rule key comes
 * from the path; the server assigns the authoritative version number.
 */
public final class AllocationRuleRequests {

    private AllocationRuleRequests() {
    }

    public record RuleVersionRequest(
            String name,
            String providerCode,
            ApiId providerAccountId,
            String matchHintType,
            String matchValue,
            Integer priority,
            ApiId targetProjectId,
            ApiId targetCostCenterId,
            ApiId targetTeamId,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    public static RuleDefinitionCommand parse(RuleVersionRequest request) {
        if (request == null) {
            throw validation("A rule definition is required.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw validation("name is required.");
        }
        if (request.providerCode() == null || request.providerCode().isBlank()) {
            throw validation("providerCode is required.");
        }
        AllocationRuleMatchType matchHintType;
        try {
            matchHintType = request.matchHintType() == null
                    ? null
                    : AllocationRuleMatchType.valueOf(request.matchHintType());
        } catch (IllegalArgumentException invalid) {
            throw validation("matchHintType must be PROVIDER_API_KEY, PROVIDER_PROJECT, "
                    + "or PROVIDER_USER.");
        }
        if (matchHintType == null) {
            throw validation("matchHintType is required.");
        }
        if (request.matchValue() == null || request.matchValue().isBlank()) {
            throw validation("matchValue is required.");
        }
        if (request.priority() == null) {
            throw validation("priority is required.");
        }
        if (request.effectiveFrom() == null) {
            throw validation("effectiveFrom is required.");
        }
        if (request.effectiveTo() != null
                && !request.effectiveFrom().isBefore(request.effectiveTo())) {
            throw validation("effectiveFrom must be before effectiveTo.");
        }
        var projectId = request.targetProjectId() == null ? null : request.targetProjectId().value();
        var costCenterId = request.targetCostCenterId() == null
                ? null : request.targetCostCenterId().value();
        var teamId = request.targetTeamId() == null ? null : request.targetTeamId().value();
        var targetCount = (projectId == null ? 0 : 1)
                + (costCenterId == null ? 0 : 1)
                + (teamId == null ? 0 : 1);
        if (targetCount != 1) {
            throw validation("Exactly one target is required: projectId, costCenterId, or teamId.");
        }
        return new RuleDefinitionCommand(
                request.name().trim(),
                request.providerCode().trim(),
                request.providerAccountId() == null ? null : request.providerAccountId().value(),
                matchHintType,
                // matchValue keeps the exact client value: trimming or folding it
                // here would silently change BINARY exact-match semantics.
                request.matchValue(),
                request.priority(),
                projectId,
                costCenterId,
                teamId,
                request.effectiveFrom(),
                request.effectiveTo());
    }

    private static DomainException validation(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invalid rule definition", detail);
    }
}
