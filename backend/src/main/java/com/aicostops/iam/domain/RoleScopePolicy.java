package com.aicostops.iam.domain;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class RoleScopePolicy {

    private static final Map<String, Set<ScopeType>> VALID_SCOPES_BY_ROLE = Map.of(
            "EMPLOYEE", Set.of(ScopeType.ORG),
            "PROJECT_OWNER", Set.of(ScopeType.PROJECT),
            "FINANCE_REVIEWER", Set.of(ScopeType.ORG, ScopeType.COST_CENTER),
            "FINANCE_ADMIN", Set.of(ScopeType.ORG),
            "SYSTEM_ADMIN", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.TEAM, ScopeType.COST_CENTER));

    private RoleScopePolicy() {
    }

    public static void requireValid(String roleCode, ScopeType scopeType) {
        if (!VALID_SCOPES_BY_ROLE.getOrDefault(roleCode, Set.of()).contains(scopeType)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Role scope is invalid", "The Role cannot be assigned at the requested scope.");
        }
    }
}
