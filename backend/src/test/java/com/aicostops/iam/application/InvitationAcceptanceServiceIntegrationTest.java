package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class InvitationAcceptanceServiceIntegrationTest extends MySqlContainerSupport {

    private static final String TOKEN = "invitation-secret-with-enough-entropy";

    @Autowired
    private InvitationAcceptanceService invitationAcceptanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long organizationId;

    @BeforeEach
    void setUp() {
        cleanRows();
        jdbcTemplate.update("DELETE FROM organization WHERE slug='invitation-org'");
        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Invitation','invitation-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug='invitation-org'", Long.class);
    }

    @AfterEach
    void restoreRoleAssignmentConstraint() {
        jdbcTemplate.execute("ALTER TABLE role_assignment DROP CHECK chk_role_assignment_scope");
        jdbcTemplate.execute("ALTER TABLE role_assignment ADD CONSTRAINT chk_role_assignment_scope CHECK (scope_type IN ('ORG','PROJECT','TEAM','COST_CENTER'))");
    }

    @Test
    void acceptsInvitationInOneTransactionAndStoresNoRawToken() {
        var invitationId = insertInvitation("invitee@invitation.test", "PENDING", Instant.now().plus(1, ChronoUnit.HOURS));

        var result = invitationAcceptanceService.accept(TOKEN,
                new AcceptInvitationCommand("Invitee", "valid-password"));

        assertThat(result.organizationId()).isEqualTo(organizationId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM invitation WHERE id=?", String.class, invitationId))
                .isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM invitation WHERE id=?", String.class, invitationId))
                .isEqualTo(sha256(TOKEN)).doesNotContain(TOKEN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='INVITATION_ACCEPTED' AND actor_user_id=?",
                Integer.class, result.userId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CAST(metadata_json AS CHAR) FROM audit_event WHERE event_type='INVITATION_ACCEPTED' AND actor_user_id=?",
                String.class, result.userId())).doesNotContain(TOKEN, "password", "secret");
    }

    @Test
    void rejectsExpiredUsedWrongTokenDuplicateEmailAndInactiveOrganization() {
        insertInvitation("expired@invitation.test", "PENDING", Instant.now().minus(1, ChronoUnit.MINUTES));
        assertRejected(TOKEN);
        assertThat(userCount("expired@invitation.test")).isZero();

        cleanInvitationsAndIdentity();
        insertInvitation("used@invitation.test", "ACCEPTED", Instant.now().plus(1, ChronoUnit.HOURS));
        assertRejected(TOKEN);

        cleanInvitationsAndIdentity();
        insertInvitation("wrong@invitation.test", "PENDING", Instant.now().plus(1, ChronoUnit.HOURS));
        assertRejected("different-token");

        cleanInvitationsAndIdentity();
        insertInvitation("duplicate@invitation.test", "PENDING", Instant.now().plus(1, ChronoUnit.HOURS));
        jdbcTemplate.update("INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at) VALUES ('duplicate@invitation.test','Existing','ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        assertRejected(TOKEN);

        cleanInvitationsAndIdentity();
        insertInvitation("inactive@invitation.test", "PENDING", Instant.now().plus(1, ChronoUnit.HOURS));
        jdbcTemplate.update("UPDATE organization SET status='DISABLED' WHERE id=?", organizationId);
        assertRejected(TOKEN);
        assertThat(userCount("inactive@invitation.test")).isZero();
    }

    @Test
    void rollsBackIdentityAndInvitationStateWhenRoleAssignmentFails() {
        var invitationId = insertInvitation("rollback-invite@invitation.test", "PENDING", Instant.now().plus(1, ChronoUnit.HOURS));
        jdbcTemplate.execute("ALTER TABLE role_assignment DROP CHECK chk_role_assignment_scope");
        jdbcTemplate.execute("ALTER TABLE role_assignment ADD CONSTRAINT chk_role_assignment_scope CHECK (scope_type <> 'ORG')");

        assertThatThrownBy(() -> invitationAcceptanceService.accept(TOKEN,
                new AcceptInvitationCommand("Rollback", "valid-password")))
                .isInstanceOf(RuntimeException.class);

        assertThat(userCount("rollback-invite@invitation.test")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM invitation WHERE id=?", String.class, invitationId))
                .isEqualTo("PENDING");
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> invitationAcceptanceService.accept(token,
                new AcceptInvitationCommand("Rejected", "valid-password")))
                .isInstanceOf(DomainException.class);
    }

    private long insertInvitation(String email, String status, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO invitation(org_id,email_normalized,token_hash,initial_role_code,status,expires_at,created_at)
                VALUES (?,?,?,'EMPLOYEE',?,?,UTC_TIMESTAMP(6))
                """, organizationId, email, sha256(TOKEN), status, expiresAt);
        return jdbcTemplate.queryForObject("SELECT id FROM invitation WHERE email_normalized=?", Long.class, email);
    }

    private int userCount(String email) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE email_normalized=?", Integer.class, email);
    }

    private void cleanRows() {
        jdbcTemplate.update("DELETE FROM audit_event");
        cleanInvitationsAndIdentity();
    }

    private void cleanInvitationsAndIdentity() {
        jdbcTemplate.update("DELETE FROM invitation");
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
