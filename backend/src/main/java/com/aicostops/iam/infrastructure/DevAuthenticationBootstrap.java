package com.aicostops.iam.infrastructure;

import com.aicostops.iam.domain.EmailAddress;
import com.aicostops.organization.infrastructure.OrganizationMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
public class DevAuthenticationBootstrap implements ApplicationRunner {

    static final String LOCAL_DEVELOPMENT_NAME = "AI CostOps Local Development";

    private final OrganizationMapper organizationMapper;
    private final DevAuthenticationBootstrapMapper bootstrapMapper;
    private final IamMapper iamMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String organizationSlug;
    private final boolean bootstrapEnabled;
    private final String bootstrapEmail;
    private final String bootstrapDisplayName;
    private final String bootstrapPassword;

    public DevAuthenticationBootstrap(
            OrganizationMapper organizationMapper,
            DevAuthenticationBootstrapMapper bootstrapMapper,
            IamMapper iamMapper,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${aicostops.auth.public-registration-org-slug:local-dev}") String organizationSlug,
            @Value("${aicostops.auth.dev-bootstrap-enabled:false}") boolean bootstrapEnabled,
            @Value("${aicostops.auth.dev-bootstrap-email:admin@example.test}") String bootstrapEmail,
            @Value("${aicostops.auth.dev-bootstrap-display-name:AI CostOps Local Admin}") String bootstrapDisplayName,
            @Value("${aicostops.auth.dev-bootstrap-password:}") String bootstrapPassword) {
        this.organizationMapper = organizationMapper;
        this.bootstrapMapper = bootstrapMapper;
        this.iamMapper = iamMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.organizationSlug = organizationSlug;
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapDisplayName = bootstrapDisplayName;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        organizationMapper.insertActiveOrganizationIfMissing(organizationSlug, LOCAL_DEVELOPMENT_NAME);
        if (!bootstrapEnabled) {
            return;
        }
        if (bootstrapPassword == null || bootstrapPassword.length() < 8) {
            throw new IllegalStateException("Development bootstrap password must be at least 8 characters");
        }

        var organizationId = iamMapper.findActiveOrganizationIdBySlug(organizationSlug);
        if (organizationId == null) {
            throw new IllegalStateException("Development bootstrap organization is unavailable");
        }
        var now = Instant.now(clock);
        var periodStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay();
        var periodEnd = periodStart.toLocalDate().plusMonths(1).atStartOfDay();
        bootstrapMapper.insertOpenBillingPeriodIfMissing(organizationId, periodStart, periodEnd, now);

        var normalizedEmail = EmailAddress.normalize(bootstrapEmail);
        if (iamMapper.countUsersByNormalizedEmail(normalizedEmail) > 0) {
            return;
        }

        iamMapper.insertUser(normalizedEmail, bootstrapDisplayName, now);
        var userId = iamMapper.lastInsertId();
        iamMapper.insertCredential(userId, passwordEncoder.encode(bootstrapPassword), now);
        iamMapper.insertOrganizationMember(organizationId, userId, now);
        var memberId = iamMapper.lastInsertId();

        // This is a local-only bootstrap identity. The roles are assigned through
        // the same role-assignment table and scope used by the normal IAM flow;
        // the password is supplied by the environment and is never logged.
        assignRole(memberId, organizationId, "EMPLOYEE", now);
        assignRole(memberId, organizationId, "FINANCE_ADMIN", now);
        assignRole(memberId, organizationId, "SYSTEM_ADMIN", now);
    }

    private void assignRole(long memberId, long organizationId, String roleCode, Instant now) {
        var roleId = iamMapper.findRoleIdByCode(roleCode);
        if (roleId == null) {
            throw new IllegalStateException("Development bootstrap role is unavailable: " + roleCode);
        }
        iamMapper.insertOrganizationRoleAssignment(memberId, roleId, organizationId, now);
    }
}
