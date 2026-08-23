package com.aicostops.iam.domain;

import java.util.Map;
import java.util.Set;

public final class M1AdminPermissionPolicy {

    private static final Map<String, Set<ScopeType>> APPLICABLE_SCOPES_BY_PERMISSION = Map.ofEntries(
            Map.entry("USER_READ", Set.of(ScopeType.ORG)),
            Map.entry("USER_MANAGE", Set.of(ScopeType.ORG)),
            Map.entry("USER_INVITE", Set.of(ScopeType.ORG)),
            Map.entry("ROLE_READ", Set.of(ScopeType.ORG)),
            Map.entry("ROLE_ASSIGN", Set.of(ScopeType.ORG)),
            Map.entry("PROJECT_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT)),
            Map.entry("PROJECT_MANAGE", Set.of(ScopeType.ORG, ScopeType.PROJECT)),
            Map.entry("PROJECT_MEMBER_MANAGE", Set.of(ScopeType.ORG, ScopeType.PROJECT)),
            Map.entry("TEAM_READ", Set.of(ScopeType.ORG, ScopeType.TEAM)),
            Map.entry("TEAM_MANAGE", Set.of(ScopeType.ORG, ScopeType.TEAM)),
            Map.entry("COST_CENTER_READ", Set.of(ScopeType.ORG, ScopeType.COST_CENTER)),
            Map.entry("COST_CENTER_MANAGE", Set.of(ScopeType.ORG, ScopeType.COST_CENTER)),
            Map.entry("PROVIDER_ACCOUNT_READ", Set.of(ScopeType.ORG)),
            Map.entry("PROVIDER_ACCOUNT_MANAGE", Set.of(ScopeType.ORG)),
            Map.entry("EVIDENCE_UPLOAD_OWN", Set.of(ScopeType.ORG)),
            Map.entry("EVIDENCE_UPLOAD_PROVIDER", Set.of(ScopeType.ORG)),
            Map.entry("EVIDENCE_READ", Set.of(ScopeType.ORG)),
            Map.entry("EVIDENCE_DOWNLOAD", Set.of(ScopeType.ORG)),
            Map.entry("EXPENSE_CREATE_OWN", Set.of(ScopeType.ORG)),
            Map.entry("EXPENSE_READ_OWN", Set.of(ScopeType.ORG)),
            Map.entry("EXPENSE_SUBMIT_OWN", Set.of(ScopeType.ORG)),
            Map.entry("EXPENSE_REVIEW", Set.of(ScopeType.ORG)),
            Map.entry("EXPENSE_POST", Set.of(ScopeType.ORG)),
            Map.entry("IMPORT_READ", Set.of(ScopeType.ORG)),
            Map.entry("IMPORT_RETRY", Set.of(ScopeType.ORG)),
            Map.entry("IMPORT_CONFIRM", Set.of(ScopeType.ORG)),
            Map.entry("IMPORT_CANCEL", Set.of(ScopeType.ORG)),
            Map.entry("COST_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
            Map.entry("DUPLICATE_REVIEW", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
            Map.entry("ALLOCATION_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
            Map.entry("ALLOCATION_EDIT", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
            Map.entry("ALLOCATION_CONFIRM", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
            Map.entry("ALLOCATION_RULE_MANAGE", Set.of(ScopeType.ORG)),
            Map.entry("BUDGET_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT,
                    ScopeType.TEAM, ScopeType.COST_CENTER)),
            Map.entry("BUDGET_MANAGE", Set.of(ScopeType.ORG)),
            Map.entry("LEDGER_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT,
                    ScopeType.TEAM, ScopeType.COST_CENTER)),
            Map.entry("LEDGER_POST", Set.of(ScopeType.ORG)),
            Map.entry("LEDGER_CORRECT", Set.of(ScopeType.ORG)),
            Map.entry("COMMITMENT_REQUEST", Set.of(ScopeType.ORG, ScopeType.PROJECT,
                    ScopeType.TEAM, ScopeType.COST_CENTER)),
            Map.entry("COMMITMENT_APPROVE", Set.of(ScopeType.ORG, ScopeType.PROJECT,
                    ScopeType.TEAM, ScopeType.COST_CENTER)),
            Map.entry("COMMITMENT_RELEASE", Set.of(ScopeType.ORG, ScopeType.PROJECT,
                    ScopeType.TEAM, ScopeType.COST_CENTER)),
            // M6 reconciliation/period-end operations are intentionally
            // organization-wide finance capabilities. The V3 seed controls
            // which finance roles receive them; SYSTEM_ADMIN does not inherit
            // them merely because the scope is ORG.
            Map.entry("RECONCILIATION_READ", Set.of(ScopeType.ORG)),
            Map.entry("RECONCILIATION_RUN", Set.of(ScopeType.ORG)),
            Map.entry("RECONCILIATION_RESOLVE", Set.of(ScopeType.ORG)),
            Map.entry("PERIOD_READ", Set.of(ScopeType.ORG)),
            Map.entry("PERIOD_CLOSE", Set.of(ScopeType.ORG)),
            Map.entry("PERIOD_REOPEN", Set.of(ScopeType.ORG)),
            // AIC-065: the audit log is an organization-wide compliance view;
            // the V3 seed grants AUDIT_READ to finance/admin roles at ORG
            // scope only, so a query is always pinned to one organization.
            Map.entry("AUDIT_READ", Set.of(ScopeType.ORG)));

    private M1AdminPermissionPolicy() {
    }

    public static Set<ScopeType> applicableScopes(String permissionCode) {
        return APPLICABLE_SCOPES_BY_PERMISSION.getOrDefault(permissionCode, Set.of());
    }
}
