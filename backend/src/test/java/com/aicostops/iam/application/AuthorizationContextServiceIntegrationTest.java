package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class AuthorizationContextServiceIntegrationTest extends MySqlContainerSupport {

    private static final Set<String> SYSTEM_ADMIN_PERMISSIONS = Set.of(
            "USER_READ", "USER_MANAGE", "USER_INVITE", "ROLE_READ", "ROLE_ASSIGN",
            "PROJECT_READ", "PROJECT_MANAGE", "PROJECT_MEMBER_MANAGE", "TEAM_READ", "TEAM_MANAGE",
            "COST_CENTER_READ", "COST_CENTER_MANAGE", "PROVIDER_ACCOUNT_READ",
            "PROVIDER_ACCOUNT_MANAGE", "AUDIT_READ");

    @Autowired
    private AuthorizationContextService authorizationContextService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long organizationId;
    private long userId;
    private long organizationMemberId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM organization");

        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('Authorization Test','authorization-test','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug='authorization-test'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES ('authorization@context.test','Authorization Test','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized='authorization@context.test'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (? ,? ,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, userId);
        organizationMemberId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE user_id=?", Long.class, userId);
    }

    @Test
    void loadsAllSeededPermissionsWithExactAssignmentScope() {
        assign("SYSTEM_ADMIN", "ORG", organizationId);

        var context = authorizationContextService.fresh(new AuthenticatedUser(userId, 7));

        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.organizationId()).isEqualTo(organizationId);
        assertThat(context.organizationMemberId()).isEqualTo(organizationMemberId);
        assertThat(context.securityVersion()).isEqualTo(7);
        assertThat(context.roleCodes()).containsExactly("SYSTEM_ADMIN");
        assertThat(context.grants()).containsExactlyInAnyOrderElementsOf(SYSTEM_ADMIN_PERMISSIONS.stream()
                .map(permission -> new ScopedPermissionGrant(permission, ScopeType.ORG, organizationId))
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void rejectsInactiveOrAmbiguousMembership() {
        jdbcTemplate.update("UPDATE organization_member SET status='DISABLED' WHERE id=?", organizationMemberId);

        assertSessionExpired(userId, 7);

        jdbcTemplate.update("UPDATE organization_member SET status='ACTIVE' WHERE id=?", organizationMemberId);
        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('Second Authorization Test','second-authorization-test','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        var secondOrganizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug='second-authorization-test'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (? ,? ,'ACTIVE',UTC_TIMESTAMP(6))
                """, secondOrganizationId, userId);

        assertSessionExpired(userId, 7);
    }

    @Test
    void rejectsSecurityVersionMismatch() {
        assertSessionExpired(userId, 6);
    }

    @Test
    void keepsFinanceReviewerCostCenterGrants() {
        assign("FINANCE_REVIEWER", "COST_CENTER", 9);

        var context = authorizationContextService.fresh(new AuthenticatedUser(userId, 7));

        assertThat(context.grants()).contains(
                new ScopedPermissionGrant("LEDGER_POST", ScopeType.COST_CENTER, 9),
                new ScopedPermissionGrant("IMPORT_CONFIRM", ScopeType.COST_CENTER, 9),
                new ScopedPermissionGrant("AUDIT_READ", ScopeType.COST_CENTER, 9));
    }

    @Test
    void constructsContextWithGrantsBeforeRoleCodes() {
        var grants = Set.of(new ScopedPermissionGrant("USER_READ", ScopeType.ORG, 2));
        var roleCodes = Set.of("SYSTEM_ADMIN");

        var context = new AuthorizationContext(1, 2, 3, 4, grants, roleCodes);

        assertThat(context.grants()).isEqualTo(grants);
        assertThat(context.roleCodes()).isEqualTo(roleCodes);
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbcTemplate.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?, id, ?, ?, NULL, UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, organizationMemberId, scopeType, scopeId, roleCode);
    }

    private void assertSessionExpired(long authenticatedUserId, long securityVersion) {
        assertThatThrownBy(() -> authorizationContextService.fresh(
                new AuthenticatedUser(authenticatedUserId, securityVersion)))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ProblemCode.AUTH_SESSION_EXPIRED);
                    assertThat(exception.status().value()).isEqualTo(401);
                });
    }
}
