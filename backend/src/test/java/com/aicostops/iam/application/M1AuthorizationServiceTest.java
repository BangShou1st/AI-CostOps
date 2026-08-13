package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class M1AuthorizationServiceTest {

    private final M1AuthorizationService authorizationService = new M1AuthorizationService();

    @Test
    void missingPermissionIsForbidden() {
        assertForbidden(() -> authorizationService.requireOrg(context(Set.of(), Set.of()), "USER_READ"));
        assertForbidden(() -> authorizationService.requireOrg(context(Set.of(), Set.of("SYSTEM_ADMIN")), "USER_READ"));
    }

    @Test
    void matchingOrgAndResourceGrantsSucceed() {
        var organizationGrant = context(Set.of(grant("PROJECT_MANAGE", ScopeType.ORG, 10)), Set.of());
        var resourceGrant = context(Set.of(grant("PROJECT_MANAGE", ScopeType.PROJECT, 42)), Set.of());

        assertThatCode(() -> authorizationService.requireOrg(
                context(Set.of(grant("USER_READ", ScopeType.ORG, 10)), Set.of()), "USER_READ"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizationService.requireResource(
                organizationGrant, "PROJECT_MANAGE", ScopeType.PROJECT, 42)).doesNotThrowAnyException();
        assertThatCode(() -> authorizationService.requireResource(
                resourceGrant, "PROJECT_MANAGE", ScopeType.PROJECT, 42)).doesNotThrowAnyException();
    }

    @Test
    void wrongResourceScopeIsNotFound() {
        var context = context(Set.of(grant("PROJECT_MANAGE", ScopeType.PROJECT, 42)), Set.of());

        assertNotFound(() -> authorizationService.requireResource(context, "PROJECT_MANAGE", ScopeType.PROJECT, 99));
        assertNotFound(() -> authorizationService.requireResource(context, "PROJECT_MANAGE", ScopeType.TEAM, 42));
    }

    @Test
    void listScopesAreOrganizationWideOrExactTypedIds() {
        var organizationScope = authorizationService.requireList(
                context(Set.of(grant("PROJECT_READ", ScopeType.ORG, 10)), Set.of()),
                "PROJECT_READ", ScopeType.PROJECT);
        var typedScope = authorizationService.requireList(
                context(Set.of(
                        grant("PROJECT_READ", ScopeType.PROJECT, 42),
                        grant("PROJECT_READ", ScopeType.PROJECT, 84)), Set.of()),
                "PROJECT_READ", ScopeType.PROJECT);

        assertThat(organizationScope).isEqualTo(new ResourceScope(true, Set.of()));
        assertThat(typedScope).isEqualTo(new ResourceScope(false, Set.of(42L, 84L)));
    }

    @Test
    void emptyScopedIdsRemainEmpty() {
        var scope = authorizationService.requireList(
                context(Set.of(grant("PROJECT_READ", ScopeType.PROJECT, 42)), Set.of()),
                "PROJECT_READ", ScopeType.TEAM);

        assertThat(scope).isEqualTo(new ResourceScope(false, Set.of()));
    }

    @Test
    void financeGrantIsNotM1AdminAccess() {
        var financeContext = context(Set.of(grant("LEDGER_POST", ScopeType.COST_CENTER, 9)), Set.of("FINANCE_ADMIN"));

        assertForbidden(() -> authorizationService.requireOrg(financeContext, "LEDGER_POST"));
        assertForbidden(() -> authorizationService.requireResource(
                financeContext, "LEDGER_POST", ScopeType.COST_CENTER, 9));
    }

    private static AuthorizationContext context(Set<ScopedPermissionGrant> grants, Set<String> roleCodes) {
        return new AuthorizationContext(1, 10, 100, 7, grants, roleCodes);
    }

    private static ScopedPermissionGrant grant(String permissionCode, ScopeType scopeType, long scopeId) {
        return new ScopedPermissionGrant(permissionCode, scopeType, scopeId);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ProblemCode.FORBIDDEN);
            assertThat(exception.status().value()).isEqualTo(403);
        });
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DomainException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
            assertThat(exception.status().value()).isEqualTo(404);
        });
    }
}
