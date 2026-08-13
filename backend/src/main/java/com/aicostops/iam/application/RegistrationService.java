package com.aicostops.iam.application;

import com.aicostops.iam.domain.EmailAddress;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final IamMapper iamMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final boolean allowPublicRegistration;
    private final String organizationSlug;

    public RegistrationService(
            IamMapper iamMapper,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${aicostops.auth.allow-public-registration:false}") boolean allowPublicRegistration,
            @Value("${aicostops.auth.public-registration-org-slug:local-dev}") String organizationSlug) {
        this.iamMapper = iamMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.allowPublicRegistration = allowPublicRegistration;
        this.organizationSlug = organizationSlug;
    }

    @Transactional
    public RegisteredIdentity register(RegisterCommand command) {
        return register(command, allowPublicRegistration);
    }

    @Transactional
    RegisteredIdentity register(RegisterCommand command, boolean registrationAllowed) {
        if (!registrationAllowed) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Public registration is disabled", "Public registration is not enabled for this environment.");
        }
        if (command == null || command.displayName() == null || command.displayName().isBlank()
                || command.displayName().trim().length() > 200 || command.password() == null
                || command.password().length() < 8) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Registration validation failed", "Email, display name, or password is invalid.");
        }

        var email = EmailAddress.normalize(command.email());
        var organizationId = iamMapper.findActiveOrganizationIdBySlug(organizationSlug);
        if (organizationId == null) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                    "Registration organization unavailable",
                    "The configured registration organization is unavailable.");
        }
        if (iamMapper.countUsersByNormalizedEmail(email) > 0) {
            throw duplicateEmail();
        }
        var employeeRoleId = iamMapper.findRoleIdByCode("EMPLOYEE");
        if (employeeRoleId == null) {
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                    "Registration role unavailable", "The default registration role is unavailable.");
        }

        var now = clock.instant();
        try {
            iamMapper.insertUser(email, command.displayName().trim(), now);
            var userId = iamMapper.lastInsertId();
            iamMapper.insertCredential(userId, passwordEncoder.encode(command.password()), now);
            iamMapper.insertOrganizationMember(organizationId, userId, now);
            var memberId = iamMapper.lastInsertId();
            iamMapper.insertOrganizationRoleAssignment(memberId, employeeRoleId, organizationId, now);
            return new RegisteredIdentity(userId, memberId, organizationId);
        } catch (DuplicateKeyException exception) {
            throw duplicateEmail();
        }
    }

    private DomainException duplicateEmail() {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Registration conflict", "An account with this email already exists.");
    }
}
