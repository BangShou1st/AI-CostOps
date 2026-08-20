package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.iam.application.M1AuthorizationService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class M6AuthorizationIntegrationTest extends AllocationApiTestSupport {

    @Autowired AuthorizationContextService authorizationContexts;

    private final M1AuthorizationService authorization = new M1AuthorizationService();

    @Test
    void seededFinanceRolesPreserveM6AuthorityBoundary() {
        var reviewerUserId = insertTestUser("m6-reviewer-" + System.nanoTime() + "@example.com");
        var reviewerMemberId = insertTestMember(reviewerUserId);
        assignRole(reviewerMemberId, "FINANCE_REVIEWER");

        var adminUserId = insertTestUser("m6-admin-" + System.nanoTime() + "@example.com");
        var adminMemberId = insertTestMember(adminUserId);
        assignRole(adminMemberId, "FINANCE_ADMIN");

        var systemUserId = insertTestUser("m6-system-" + System.nanoTime() + "@example.com");
        var systemMemberId = insertTestMember(systemUserId);
        assignRole(systemMemberId, "SYSTEM_ADMIN");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        var reviewer = authorizationContexts.fresh(new AuthenticatedUser(reviewerUserId, 7));
        assertThatCode(() -> authorization.requireOrg(reviewer, "RECONCILIATION_READ"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorization.requireOrg(reviewer, "RECONCILIATION_RUN"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorization.requireOrg(reviewer, "RECONCILIATION_RESOLVE"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorization.requireOrg(reviewer, "PERIOD_READ"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> authorization.requireOrg(reviewer, "PERIOD_CLOSE"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorization.requireOrg(reviewer, "PERIOD_REOPEN"))
                .isInstanceOf(DomainException.class);

        var admin = authorizationContexts.fresh(new AuthenticatedUser(adminUserId, 7));
        assertThatCode(() -> authorization.requireOrg(admin, "PERIOD_CLOSE"))
                .doesNotThrowAnyException();
        assertThatCode(() -> authorization.requireOrg(admin, "PERIOD_REOPEN"))
                .doesNotThrowAnyException();

        var system = authorizationContexts.fresh(new AuthenticatedUser(systemUserId, 7));
        assertThatThrownBy(() -> authorization.requireOrg(system, "RECONCILIATION_READ"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> authorization.requireOrg(system, "PERIOD_CLOSE"))
                .isInstanceOf(DomainException.class);
    }

    private long insertTestUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(
                  email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, email);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertTestMember(long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void assignRole(long memberId, String roleCode) {
        jdbc.update("""
                INSERT INTO role_assignment(
                  org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,r.id,'ORG',?,NULL,UTC_TIMESTAMP(6)
                FROM `role` r WHERE r.code=?
                """, memberId, orgId, roleCode);
    }
}
