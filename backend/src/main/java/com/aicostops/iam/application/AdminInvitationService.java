package com.aicostops.iam.application;

import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.api.CreateInvitationRequest;
import com.aicostops.iam.api.InvitationResponse;
import com.aicostops.iam.domain.EmailAddress;
import com.aicostops.iam.domain.RoleScopePolicy;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.iam.domain.TokenDigest;
import com.aicostops.iam.infrastructure.IamAdminMapper;
import com.aicostops.shared.json.ApiId;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminInvitationService {

    private static final int TOKEN_BYTES = 32;

    private final AuthorizationContextService authorizationContexts;
    private final IamAdminMapper mapper;
    private final InvitationDelivery delivery;
    private final AuditService audit;
    private final Clock clock;
    private final Duration defaultLifetime;
    private final Duration maxLifetime;
    private final TransactionTemplate transactions;
    private final SecureRandom secureRandom = new SecureRandom();
    private final M1AuthorizationService authorization = new M1AuthorizationService();

    public AdminInvitationService(
            AuthorizationContextService authorizationContexts,
            IamAdminMapper mapper,
            InvitationDelivery delivery,
            AuditService audit,
            Clock clock,
            TransactionTemplate transactions,
            @Value("${aicostops.iam.invitation-default-lifetime:72h}") Duration defaultLifetime,
            @Value("${aicostops.iam.invitation-max-lifetime:168h}") Duration maxLifetime) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
        this.delivery = delivery;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
        this.defaultLifetime = defaultLifetime;
        this.maxLifetime = maxLifetime;
    }

    public InvitationResponse create(AuthenticatedUser authenticatedUser, CreateInvitationRequest request) {
        var context = authorizationContexts.current(authenticatedUser);
        authorization.requireOrg(context, "USER_INVITE");
        var email = normalizeEmail(request);
        var roleCode = requireInitialRole(request);
        var lifetime = requireLifetime(request);
        if (mapper.findRoleIdByCode(roleCode) == null) {
            throw validationFailed("The initial Role does not exist.");
        }
        RoleScopePolicy.requireValid(roleCode, ScopeType.ORG);
        if (mapper.countConflictingInvitationIdentities(email, context.organizationId()) > 0) {
            throw new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                    "Invitation conflict", "An active identity or organization member already uses this email.");
        }

        delivery.requireAvailable();
        return transactions.execute(status -> persistAndDeliver(
                context.organizationId(), context.organizationMemberId(), context.userId(),
                email, roleCode, lifetime));
    }

    private InvitationResponse persistAndDeliver(
            long organizationId,
            long inviterMemberId,
            long actorUserId,
            String email,
            String roleCode,
            Duration lifetime) {
        var createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var expiresAt = createdAt.plus(lifetime);
        var rawToken = generateToken();
        if (mapper.insertInvitation(organizationId, email, TokenDigest.sha256(rawToken), roleCode,
                expiresAt, inviterMemberId, createdAt) != 1) {
            throw new IllegalStateException("Invitation must insert exactly one row");
        }
        var invitationId = mapper.lastInsertId();
        audit.append("INVITATION_CREATED", organizationId, actorUserId,
                "INVITATION", invitationId, Map.of(
                        "email", email,
                        "initialRoleCode", roleCode,
                        "expiresAt", expiresAt.toString()));
        deliver(email, rawToken);
        return new InvitationResponse(ApiId.of(invitationId), email, roleCode, "PENDING", expiresAt, createdAt);
    }

    private void deliver(String email, String rawToken) {
        try {
            delivery.deliver(email, rawToken);
        } catch (DomainException exception) {
            if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE
                    && exception.code() == ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE) {
                throw exception;
            }
            throw deliveryUnavailable();
        } catch (RuntimeException exception) {
            throw deliveryUnavailable();
        }
    }

    private String generateToken() {
        var randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String normalizeEmail(CreateInvitationRequest request) {
        if (request == null) {
            throw validationFailed("Invitation request is required.");
        }
        try {
            return EmailAddress.normalize(request.email());
        } catch (IllegalArgumentException exception) {
            throw validationFailed("Email is invalid.");
        }
    }

    private String requireInitialRole(CreateInvitationRequest request) {
        if (request.initialRoleCode() == null || request.initialRoleCode().isBlank()) {
            throw validationFailed("Initial Role is required.");
        }
        return request.initialRoleCode().trim();
    }

    private Duration requireLifetime(CreateInvitationRequest request) {
        var lifetime = request.expiresInHours() == null
                ? defaultLifetime
                : Duration.ofHours(request.expiresInHours());
        if (lifetime.compareTo(Duration.ofHours(1)) < 0 || lifetime.compareTo(maxLifetime) > 0) {
            throw validationFailed("Invitation lifetime must be between 1 and 168 hours.");
        }
        return lifetime;
    }

    private DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Invitation validation failed", detail);
    }

    private DomainException deliveryUnavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                "Invitation delivery unavailable", "Invitation delivery is temporarily unavailable.");
    }
}
