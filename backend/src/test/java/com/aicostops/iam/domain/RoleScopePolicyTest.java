package com.aicostops.iam.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RoleScopePolicyTest {

    @ParameterizedTest(name = "{0} at {1} is valid: {2}")
    @MethodSource("roleScopeMatrix")
    @DisplayName("roleScopeMatrixMatchesSpec")
    void roleScopeMatrixMatchesSpec(String roleCode, ScopeType scopeType, boolean valid) {
        if (valid) {
            assertThatCode(() -> RoleScopePolicy.requireValid(roleCode, scopeType)).doesNotThrowAnyException();
            return;
        }

        assertThatThrownBy(() -> RoleScopePolicy.requireValid(roleCode, scopeType))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                    org.assertj.core.api.Assertions.assertThat(exception.status().value()).isEqualTo(400);
                });
    }

    private static Stream<Arguments> roleScopeMatrix() {
        return Stream.of(
                Arguments.of("EMPLOYEE", ScopeType.ORG, true),
                Arguments.of("EMPLOYEE", ScopeType.PROJECT, false),
                Arguments.of("EMPLOYEE", ScopeType.TEAM, false),
                Arguments.of("EMPLOYEE", ScopeType.COST_CENTER, false),
                Arguments.of("PROJECT_OWNER", ScopeType.ORG, false),
                Arguments.of("PROJECT_OWNER", ScopeType.PROJECT, true),
                Arguments.of("PROJECT_OWNER", ScopeType.TEAM, false),
                Arguments.of("PROJECT_OWNER", ScopeType.COST_CENTER, false),
                Arguments.of("FINANCE_REVIEWER", ScopeType.ORG, true),
                Arguments.of("FINANCE_REVIEWER", ScopeType.PROJECT, false),
                Arguments.of("FINANCE_REVIEWER", ScopeType.TEAM, false),
                Arguments.of("FINANCE_REVIEWER", ScopeType.COST_CENTER, true),
                Arguments.of("FINANCE_ADMIN", ScopeType.ORG, true),
                Arguments.of("FINANCE_ADMIN", ScopeType.PROJECT, false),
                Arguments.of("FINANCE_ADMIN", ScopeType.TEAM, false),
                Arguments.of("FINANCE_ADMIN", ScopeType.COST_CENTER, false),
                Arguments.of("SYSTEM_ADMIN", ScopeType.ORG, true),
                Arguments.of("SYSTEM_ADMIN", ScopeType.PROJECT, true),
                Arguments.of("SYSTEM_ADMIN", ScopeType.TEAM, true),
                Arguments.of("SYSTEM_ADMIN", ScopeType.COST_CENTER, true));
    }
}
