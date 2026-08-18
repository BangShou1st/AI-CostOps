package com.aicostops.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class M1AdminPermissionPolicyTest {

    @ParameterizedTest(name = "{0} applies to {1}")
    @MethodSource("permissionApplicability")
    @DisplayName("adminPermissionApplicabilityMatchesSpec")
    void adminPermissionApplicabilityMatchesSpec(String permissionCode, Set<ScopeType> scopes) {
        assertThat(M1AdminPermissionPolicy.applicableScopes(permissionCode)).isEqualTo(scopes);
    }

    private static Stream<Arguments> permissionApplicability() {
        return Stream.of(
                Arguments.of("USER_READ", Set.of(ScopeType.ORG)),
                Arguments.of("USER_MANAGE", Set.of(ScopeType.ORG)),
                Arguments.of("USER_INVITE", Set.of(ScopeType.ORG)),
                Arguments.of("ROLE_READ", Set.of(ScopeType.ORG)),
                Arguments.of("ROLE_ASSIGN", Set.of(ScopeType.ORG)),
                Arguments.of("PROJECT_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT)),
                Arguments.of("PROJECT_MANAGE", Set.of(ScopeType.ORG, ScopeType.PROJECT)),
                Arguments.of("PROJECT_MEMBER_MANAGE", Set.of(ScopeType.ORG, ScopeType.PROJECT)),
                Arguments.of("TEAM_READ", Set.of(ScopeType.ORG, ScopeType.TEAM)),
                Arguments.of("TEAM_MANAGE", Set.of(ScopeType.ORG, ScopeType.TEAM)),
                Arguments.of("COST_CENTER_READ", Set.of(ScopeType.ORG, ScopeType.COST_CENTER)),
                Arguments.of("COST_CENTER_MANAGE", Set.of(ScopeType.ORG, ScopeType.COST_CENTER)),
                Arguments.of("PROVIDER_ACCOUNT_READ", Set.of(ScopeType.ORG)),
                Arguments.of("PROVIDER_ACCOUNT_MANAGE", Set.of(ScopeType.ORG)),
                Arguments.of("IMPORT_READ", Set.of(ScopeType.ORG)),
                Arguments.of("IMPORT_RETRY", Set.of(ScopeType.ORG)),
                Arguments.of("IMPORT_CONFIRM", Set.of(ScopeType.ORG)),
                Arguments.of("IMPORT_CANCEL", Set.of(ScopeType.ORG)),
                Arguments.of("COST_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
                Arguments.of("DUPLICATE_REVIEW", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
                Arguments.of("ALLOCATION_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
                Arguments.of("ALLOCATION_EDIT", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
                Arguments.of("ALLOCATION_CONFIRM", Set.of(ScopeType.ORG, ScopeType.PROJECT, ScopeType.COST_CENTER)),
                Arguments.of("ALLOCATION_RULE_MANAGE", Set.of(ScopeType.ORG)),
                Arguments.of("BUDGET_READ", Set.of(ScopeType.ORG, ScopeType.PROJECT,
                        ScopeType.TEAM, ScopeType.COST_CENTER)),
                Arguments.of("BUDGET_MANAGE", Set.of(ScopeType.ORG)));
    }
}
