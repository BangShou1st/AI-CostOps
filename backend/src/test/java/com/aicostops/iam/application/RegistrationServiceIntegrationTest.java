package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=registration-org"
})
@Tag("integration")
class RegistrationServiceIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpOrganization() {
        cleanIdentityRows();
        jdbcTemplate.update("DELETE FROM organization WHERE slug='registration-org'");
        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Registration','registration-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    }

    @AfterEach
    void restoreRoleAssignmentScopeConstraint() {
        jdbcTemplate.execute("ALTER TABLE role_assignment DROP CHECK chk_role_assignment_scope");
        jdbcTemplate.execute("ALTER TABLE role_assignment ADD CONSTRAINT chk_role_assignment_scope CHECK (scope_type IN ('ORG','PROJECT','TEAM','COST_CENTER'))");
    }

    @Test
    void rejectsRegistrationWhenTheFeatureFlagIsDisabled() {
        assertThatThrownBy(() -> registrationService.register(
                new RegisterCommand("disabled@registration.test", "Disabled", "valid-password"), false))
                .isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).status().value())
                .isEqualTo(403);
    }

    @Test
    void createsNormalizedIdentityCredentialMembershipAndEmployeeRole() {
        var result = registrationService.register(
                new RegisterCommand("  PERSON@REGISTRATION.TEST ", "Person", "valid-password"), true);

        assertThat(result.userId()).isPositive();
        assertThat(result.organizationMemberId()).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT email_normalized FROM app_user WHERE id=?", String.class, result.userId()))
                .isEqualTo("person@registration.test");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user_credential WHERE user_id=?", String.class, result.userId()))
                .startsWith("{bcrypt}");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT r.code FROM role_assignment ra
                JOIN `role` r ON r.id=ra.role_id
                WHERE ra.org_member_id=?
                """, String.class, result.organizationMemberId())).isEqualTo("EMPLOYEE");
    }

    @Test
    void rejectsDuplicateNormalizedEmailWithoutAddingMembership() {
        registrationService.register(new RegisterCommand(
                "duplicate@registration.test", "First", "valid-password"), true);

        assertThatThrownBy(() -> registrationService.register(new RegisterCommand(
                " DUPLICATE@REGISTRATION.TEST ", "Second", "valid-password"), true))
                .isInstanceOf(DomainException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE email_normalized='duplicate@registration.test'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsMissingOrInactiveConfiguredOrganizationWithoutPartialIdentity() {
        jdbcTemplate.update("DELETE FROM organization WHERE slug='registration-org'");
        assertThatThrownBy(() -> registrationService.register(new RegisterCommand(
                "missing@registration.test", "Missing", "valid-password"), true))
                .isInstanceOf(DomainException.class);
        assertThat(userCount("missing@registration.test")).isZero();

        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Inactive','registration-org','DISABLED',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        assertThatThrownBy(() -> registrationService.register(new RegisterCommand(
                "inactive@registration.test", "Inactive", "valid-password"), true))
                .isInstanceOf(DomainException.class);
        assertThat(userCount("inactive@registration.test")).isZero();
    }

    @Test
    void rollsBackAllIdentityRowsWhenRoleAssignmentFails() {
        jdbcTemplate.execute("ALTER TABLE role_assignment DROP CHECK chk_role_assignment_scope");
        jdbcTemplate.execute("ALTER TABLE role_assignment ADD CONSTRAINT chk_role_assignment_scope CHECK (scope_type <> 'ORG')");

        assertThatThrownBy(() -> registrationService.register(new RegisterCommand(
                "rollback@registration.test", "Rollback", "valid-password"), true))
                .isInstanceOf(RuntimeException.class);

        assertThat(userCount("rollback@registration.test")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credential uc JOIN app_user u ON u.id=uc.user_id WHERE u.email_normalized='rollback@registration.test'",
                Integer.class)).isZero();
    }

    private int userCount(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE email_normalized=?", Integer.class, email);
    }

    private void cleanIdentityRows() {
        jdbcTemplate.update("DELETE FROM audit_event");
        jdbcTemplate.update("DELETE FROM invitation");
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
    }
}
