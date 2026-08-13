package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.api.CreateRoleAssignmentRequest;
import com.aicostops.iam.domain.AuthorizationContext;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.ScopedPermissionGrant;
import com.aicostops.iam.infrastructure.IamAdminMapper;
import com.aicostops.iam.infrastructure.IamAdminMapper.RoleRow;
import com.aicostops.iam.infrastructure.IamAdminMapper.TargetMemberRow;
import com.aicostops.organization.infrastructure.OrganizationMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

class RoleAssignmentServiceTest {

    @Test
    void naturalAssignmentConstraintIsConflict() {
        var duplicate = duplicate("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.UQ_ROLE_ASSIGNMENT_NATURAL'");
        var fixture = fixture(duplicate);

        var error = catchThrowableOfType(DomainException.class,
                () -> fixture.service().create(fixture.actor(), fixture.request()));

        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
    }

    @Test
    void backtickQuotedNaturalAssignmentConstraintIsConflict() {
        var duplicate = duplicate("Duplicate entry '10-5-ORG-2' for key "
                + "`role_assignment.uq_role_assignment_natural`");
        var fixture = fixture(duplicate);

        var error = catchThrowableOfType(DomainException.class,
                () -> fixture.service().create(fixture.actor(), fixture.request()));

        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
    }

    @Test
    void unqualifiedNaturalAssignmentConstraintIsConflict() {
        var duplicate = duplicate("Duplicate entry '10-5-ORG-2' for key "
                + "'uq_role_assignment_natural'");
        var fixture = fixture(duplicate);

        var error = catchThrowableOfType(DomainException.class,
                () -> fixture.service().create(fixture.actor(), fixture.request()));

        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
    }

    @Test
    void unrelatedDuplicateConstraintPropagates() {
        var duplicate = duplicate("Duplicate entry '99' for key 'role_assignment.PRIMARY'");
        var fixture = fixture(duplicate);

        assertThatThrownBy(() -> fixture.service().create(fixture.actor(), fixture.request()))
                .isSameAs(duplicate);
    }

    @Test
    void unterminatedKeyQuotePropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural");
    }

    @Test
    void unquotedKeyPropagates() {
        assertDuplicatePropagates("Duplicate entry '99' for key PRIMARY");
    }

    @Test
    void leadingDotQualifierPropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'.uq_role_assignment_natural'");
    }

    @Test
    void emptyMiddleQualifierPropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment..uq_role_assignment_natural'");
    }

    @Test
    void whitespaceQualifierPropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment. .uq_role_assignment_natural'");
    }

    @Test
    void trailingDotQualifierPropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural.'");
    }

    @Test
    void validNaturalKeyFollowedByUnquotedKeyPropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural' for key PRIMARY");
    }

    @Test
    void validNaturalKeyFollowedByUnterminatedKeyPropagates() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural' for key 'PRIMARY");
    }

    @Test
    void twoValidKeySegmentsPropagate() {
        assertDuplicatePropagates("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural' for key 'role_assignment.PRIMARY'");
    }

    @Test
    void naturalConstraintPrefixWithDashSuffixPropagates() {
        var duplicate = duplicate("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural-copy'");
        var fixture = fixture(duplicate);

        assertThatThrownBy(() -> fixture.service().create(fixture.actor(), fixture.request()))
                .isSameAs(duplicate);
    }

    @Test
    void naturalConstraintPrefixWithDollarSuffixPropagates() {
        var duplicate = duplicate("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural$archive'");
        var fixture = fixture(duplicate);

        assertThatThrownBy(() -> fixture.service().create(fixture.actor(), fixture.request()))
                .isSameAs(duplicate);
    }

    @Test
    void naturalConstraintTokenOutsideKeyIdentifierPropagates() {
        var duplicate = duplicate("Duplicate entry 'uq_role_assignment_natural' "
                + "for key 'role_assignment.PRIMARY'");
        var fixture = fixture(duplicate);

        assertThatThrownBy(() -> fixture.service().create(fixture.actor(), fixture.request()))
                .isSameAs(duplicate);
    }

    @Test
    void naturalConstraintInNextSqlExceptionIsConflict() {
        var first = mysqlDuplicate("Duplicate entry '99' for key 'role_assignment.PRIMARY'");
        first.setNextException(mysqlDuplicate("Duplicate entry '10-5-ORG-2' for key "
                + "'role_assignment.uq_role_assignment_natural'"));
        var fixture = fixture(duplicate(first));

        var error = catchThrowableOfType(DomainException.class,
                () -> fixture.service().create(fixture.actor(), fixture.request()));

        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void cycleInSqlExceptionGraphPropagatesWithoutHanging() {
        var first = mysqlDuplicate("Duplicate entry '99' for key 'role_assignment.PRIMARY'");
        var second = mysqlDuplicate("Duplicate entry '100' for key 'role_assignment.other_unique'");
        first.setNextException(second);
        second.setNextException(first);
        var duplicate = duplicate(first);
        var fixture = fixture(duplicate);

        assertThatThrownBy(() -> fixture.service().create(fixture.actor(), fixture.request()))
                .isSameAs(duplicate);
    }

    private Fixture fixture(DuplicateKeyException duplicate) {
        var contexts = mock(AuthorizationContextService.class);
        var mapper = mock(IamAdminMapper.class);
        var organizations = mock(OrganizationMapper.class);
        var invalidation = mock(AuthorizationInvalidationService.class);
        var audit = mock(AuditService.class);
        var actor = new AuthenticatedUser(1L, 4L);
        var context = new AuthorizationContext(
                1L, 2L, 3L, 4L,
                Set.of(new ScopedPermissionGrant("ROLE_ASSIGN", ScopeType.ORG, 2L)),
                Set.of("SYSTEM_ADMIN"));
        var request = new CreateRoleAssignmentRequest("10", "5", "ORG", "2");

        when(contexts.fresh(actor)).thenReturn(context);
        when(mapper.lockActiveActor(1L, 4L, 3L, 2L)).thenReturn(1L);
        when(mapper.findActiveTargetMemberForUpdate(10L, 2L))
                .thenReturn(new TargetMemberRow(10L, 20L, 30L));
        when(mapper.findRole(5L)).thenReturn(new RoleRow(5L, "EMPLOYEE", "Employee"));
        when(mapper.findRoleAssignmentId(10L, 5L, "ORG", 2L)).thenReturn(null);
        when(mapper.insertRoleAssignment(
                org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq("ORG"),
                org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(3L),
                any(Instant.class))).thenThrow(duplicate);

        return new Fixture(new RoleAssignmentService(
                contexts, mapper, organizations, invalidation, audit,
                Clock.fixed(Instant.parse("2026-08-13T05:00:00Z"), ZoneOffset.UTC)), actor, request);
    }

    private void assertDuplicatePropagates(String mysqlMessage) {
        var duplicate = duplicate(mysqlMessage);
        var fixture = fixture(duplicate);

        assertThatThrownBy(() -> fixture.service().create(fixture.actor(), fixture.request()))
                .isSameAs(duplicate);
    }

    private DuplicateKeyException duplicate(String mysqlMessage) {
        return duplicate(mysqlDuplicate(mysqlMessage));
    }

    private DuplicateKeyException duplicate(SQLIntegrityConstraintViolationException cause) {
        return new DuplicateKeyException("Role assignment insert failed", cause);
    }

    private SQLIntegrityConstraintViolationException mysqlDuplicate(String mysqlMessage) {
        return new SQLIntegrityConstraintViolationException(mysqlMessage, "23000", 1062);
    }

    private record Fixture(
            RoleAssignmentService service,
            AuthenticatedUser actor,
            CreateRoleAssignmentRequest request) {
    }
}
