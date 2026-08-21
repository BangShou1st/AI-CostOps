package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aicostops.iam.api.CreateInvitationRequest;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.iam.domain.TokenDigest;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Tag("integration")
class AdminInvitationServiceIntegrationTest extends MySqlContainerSupport {

    private static final AuthenticatedUser ACTOR = new AuthenticatedUser(1L, 0L);

    @Autowired
    private AdminInvitationService invitations;
    @Autowired
    private InvitationAcceptanceService acceptance;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private AuthorizationContextService authorizationContexts;
    @MockitoBean
    private InvitationDelivery delivery;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;

    @BeforeEach
    void setUp() {
        reset(authorizationContexts, delivery);
        cleanRows();
        jdbcTemplate.update("DELETE FROM organization WHERE slug='admin-invitation-org'");
        jdbcTemplate.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES ('Admin Invitation','admin-invitation-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        organizationId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization WHERE slug='admin-invitation-org'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES ('inviter@invitation.test','Inviter','ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        actorUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized='inviter@invitation.test'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, actorUserId);
        actorMemberId = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, organizationId, actorUserId);
        when(authorizationContexts.current(ACTOR)).thenReturn(contextWithInviteGrant());
    }

    @Test
    void createsHashOnlyInvitationWithDefaultTtl() {
        var response = invitations.create(ACTOR,
                new CreateInvitationRequest("  New.Person@Example.COM ", "EMPLOYEE", null));

        var email = ArgumentCaptor.forClass(String.class);
        var token = ArgumentCaptor.forClass(String.class);
        verify(delivery).deliver(email.capture(), token.capture());
        assertThat(email.getValue()).isEqualTo("new.person@example.com");
        assertThat(token.getValue()).matches("[A-Za-z0-9_-]{43}").doesNotContain("=");
        assertThat(Base64.getUrlDecoder().decode(token.getValue())).hasSize(32);

        var row = jdbcTemplate.queryForMap("""
                SELECT id,org_id,email_normalized,token_hash,initial_role_code,status,expires_at,invited_by,created_at
                FROM invitation WHERE id=?
                """, response.id().value());
        assertThat(row.get("org_id")).isEqualTo(organizationId);
        assertThat(row.get("email_normalized")).isEqualTo("new.person@example.com");
        assertThat(row.get("token_hash")).isEqualTo(TokenDigest.sha256(token.getValue()))
                .asString().doesNotContain(token.getValue());
        assertThat(row.get("initial_role_code")).isEqualTo("EMPLOYEE");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("invited_by")).isEqualTo(actorMemberId);
        var createdAt = ((LocalDateTime) row.get("created_at")).toInstant(ZoneOffset.UTC);
        var expiresAt = ((LocalDateTime) row.get("expires_at")).toInstant(ZoneOffset.UTC);
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofHours(72));

        var audit = jdbcTemplate.queryForMap("""
                SELECT org_id,actor_user_id,subject_type,subject_id,CAST(metadata_json AS CHAR) metadata
                FROM audit_event WHERE event_type='INVITATION_CREATED'
                """);
        assertThat(audit.get("org_id")).isEqualTo(organizationId);
        assertThat(audit.get("actor_user_id")).isEqualTo(actorUserId);
        assertThat(audit.get("subject_type")).isEqualTo("INVITATION");
        assertThat(audit.get("subject_id")).isEqualTo(response.id().value());
        assertThat(audit.get("metadata").toString())
                .contains("new.person@example.com", "EMPLOYEE", response.expiresAt().toString())
                .doesNotContain(token.getValue(), TokenDigest.sha256(token.getValue()), "token", "secret");
        assertThat(objectMapper.writeValueAsString(response))
                .contains("new.person@example.com", "EMPLOYEE", "PENDING")
                .doesNotContain(token.getValue(), TokenDigest.sha256(token.getValue()), "token");

        var accepted = acceptance.accept(token.getValue(),
                new AcceptInvitationCommand("New Person", "valid-password"));
        assertThat(accepted.organizationId()).isEqualTo(organizationId);
        var secondUse = catchThrowableOfType(DomainException.class, () -> acceptance.accept(token.getValue(),
                new AcceptInvitationCommand("New Person", "valid-password")));
        assertThat(secondUse.status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void validatesInvitationLifetimeBounds() {
        var oneHour = invitations.create(ACTOR,
                new CreateInvitationRequest("one@example.com", "EMPLOYEE", 1));
        var maxHours = invitations.create(ACTOR,
                new CreateInvitationRequest("max@example.com", "FINANCE_ADMIN", 168));

        assertThat(lifetime(oneHour.id())).isEqualTo(Duration.ofHours(1));
        assertThat(lifetime(maxHours.id())).isEqualTo(Duration.ofHours(168));
        assertValidationFailure(new CreateInvitationRequest("zero@example.com", "EMPLOYEE", 0));
        assertValidationFailure(new CreateInvitationRequest("too-long@example.com", "EMPLOYEE", 169));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invitation", Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsProjectOwnerOrgInvitation() {
        assertValidationFailure(new CreateInvitationRequest(
                "project-owner@example.com", "PROJECT_OWNER", 24));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invitation", Integer.class)).isZero();
        verifyNoMoreInteractions(delivery);
    }

    @Test
    void rejectsUnknownInitialRole() {
        assertValidationFailure(new CreateInvitationRequest(
                "unknown-role@example.com", "NOT_A_ROLE", 24));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invitation", Integer.class)).isZero();
    }

    @Test
    void rejectsExistingActiveIdentityAndOrganizationMember() {
        jdbcTemplate.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES ('existing@example.com','Existing','ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        var existingUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized='existing@example.com'", Long.class);
        var identityError = catchThrowableOfType(DomainException.class, () -> invitations.create(ACTOR,
                new CreateInvitationRequest("existing@example.com", "EMPLOYEE", 24)));
        assertThat(identityError.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(identityError.code()).isEqualTo(ProblemCode.STATE_CONFLICT);

        jdbcTemplate.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, organizationId, existingUserId);
        var memberError = catchThrowableOfType(DomainException.class, () -> invitations.create(ACTOR,
                new CreateInvitationRequest("existing@example.com", "EMPLOYEE", 24)));
        assertThat(memberError.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(memberError.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
    }

    @Test
    void roleCodeDoesNotReplaceUserInviteGrant() {
        when(authorizationContexts.current(ACTOR)).thenReturn(new AuthorizationContext(
                actorUserId, organizationId, actorMemberId, 0L, Set.of(), Set.of("SYSTEM_ADMIN")));

        var error = catchThrowableOfType(DomainException.class, () -> invitations.create(ACTOR,
                new CreateInvitationRequest("denied@example.com", "EMPLOYEE", 24)));

        assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(error.code()).isEqualTo(ProblemCode.FORBIDDEN);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invitation", Integer.class)).isZero();
    }

    @Test
    void deliveryFailureRollsBack() {
        doThrow(new IllegalStateException("mailbox unavailable"))
                .when(delivery).deliver(anyString(), anyString());

        var error = catchThrowableOfType(DomainException.class, () -> invitations.create(ACTOR,
                new CreateInvitationRequest("rollback@example.com", "EMPLOYEE", 24)));

        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.code()).isEqualTo(ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE);
        assertThat(error.getMessage()).doesNotContain("mailbox unavailable");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invitation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='INVITATION_CREATED'", Integer.class)).isZero();
    }

    private AuthorizationContext contextWithInviteGrant() {
        return new AuthorizationContext(actorUserId, organizationId, actorMemberId, 0L,
                Set.of(new ScopedPermissionGrant("USER_INVITE", ScopeType.ORG, organizationId)), Set.of());
    }

    private Duration lifetime(ApiId invitationId) {
        return jdbcTemplate.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND,created_at,expires_at)
                FROM invitation WHERE id=?
                """, (resultSet, rowNumber) -> Duration.ofNanos(resultSet.getLong(1) * 1_000L),
                invitationId.value());
    }

    private void assertValidationFailure(CreateInvitationRequest request) {
        var error = catchThrowableOfType(DomainException.class, () -> invitations.create(ACTOR, request));
        assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
    }

    private void cleanRows() {
        jdbcTemplate.update("DELETE FROM audit_event");
        jdbcTemplate.update("DELETE FROM invitation");
        jdbcTemplate.update("DELETE FROM role_assignment");
        // M6 close/reconciliation history references organization members.
        jdbcTemplate.update("DELETE FROM period_close_check");
        jdbcTemplate.update("DELETE FROM period_close_run");
        jdbcTemplate.update("DELETE FROM reconciliation_case");
        jdbcTemplate.update("DELETE FROM reconciliation_run");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
    }
}
