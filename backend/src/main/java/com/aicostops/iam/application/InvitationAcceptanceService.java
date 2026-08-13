package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.domain.EmailAddress;
import com.aicostops.iam.domain.TokenDigest;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationAcceptanceService {

    private final IamMapper iamMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;

    public InvitationAcceptanceService(
            IamMapper iamMapper,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            Clock clock) {
        this.iamMapper = iamMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public RegisteredIdentity accept(String rawToken, AcceptInvitationCommand command) {
        validateCommand(command);
        var invitation = iamMapper.findInvitationForUpdate(TokenDigest.sha256(rawToken));
        var now = clock.instant();
        if (invitation == null || !"PENDING".equals(invitation.status())
                || !invitation.expiresAt().isAfter(now)) {
            throw invalidInvitation();
        }
        if (!"ACTIVE".equals(invitation.organizationStatus())) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Invitation organization is inactive", "The invitation cannot be accepted.");
        }
        var email = EmailAddress.normalize(invitation.emailNormalized());
        if (iamMapper.countUsersByNormalizedEmail(email) > 0) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Invitation conflict", "An account with this email already exists.");
        }
        var roleId = iamMapper.findRoleIdByCode(invitation.initialRoleCode());
        if (roleId == null) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Invitation role is unavailable", "The invitation cannot be accepted.");
        }

        try {
            iamMapper.insertUser(email, command.displayName().trim(), now);
            var userId = iamMapper.lastInsertId();
            iamMapper.insertCredential(userId, passwordEncoder.encode(command.password()), now);
            iamMapper.insertOrganizationMember(invitation.orgId(), userId, now);
            var memberId = iamMapper.lastInsertId();
            iamMapper.insertOrganizationRoleAssignment(memberId, roleId, invitation.orgId(), now);
            if (iamMapper.markInvitationAccepted(invitation.id(), userId, now) != 1) {
                throw invalidInvitation();
            }
            auditService.append("INVITATION_ACCEPTED", invitation.orgId(), userId,
                    "INVITATION", invitation.id(), Map.of("invitationId", Long.toString(invitation.id())));
            return new RegisteredIdentity(userId, memberId, invitation.orgId());
        } catch (DuplicateKeyException exception) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Invitation conflict", "The invitation cannot be accepted.");
        }
    }

    private void validateCommand(AcceptInvitationCommand command) {
        if (command == null || command.displayName() == null || command.displayName().isBlank()
                || command.displayName().trim().length() > 200 || command.password() == null
                || command.password().length() < 8) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Invitation validation failed", "Display name or password is invalid.");
        }
    }

    private DomainException invalidInvitation() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Invitation is invalid", "The invitation is expired, already used, or invalid.");
    }
}
