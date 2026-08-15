package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.iam.application.InvitationDelivery;
import com.aicostops.iam.application.PasswordResetDelivery;
import com.aicostops.testsupport.MySqlContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression for the `local` profile group: daily development activates only
 * `local` (for example `-Dspring-boot.run.profiles=local`), which must include
 * the dev-only development behavior. Without the group, the !dev production
 * placeholders would win and a clean database would never bootstrap the
 * local-dev organization nor write dev mailboxes.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("integration")
class LocalProfileDevBehaviorIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void requestingOnlyLocalProfileAlsoActivatesDevProfile() {
        assertThat(environment.acceptsProfiles(Profiles.of("local"))).isTrue();
        assertThat(environment.acceptsProfiles(Profiles.of("dev"))).isTrue();
    }

    @Test
    void localProfileUsesDevAuthenticationBootstrapInsteadOfProductionPlaceholder() {
        var runner = applicationContext.getBeansOfType(ApplicationRunner.class).values().stream()
                .filter(candidate -> candidate.getClass().getSimpleName().contains("DevAuthenticationBootstrap"))
                .findFirst()
                .orElseThrow();
        assertThat(runner).isInstanceOf(DevAuthenticationBootstrap.class);
    }

    @Test
    void localProfileUsesFileBackedMailboxesInsteadOfUnavailablePlaceholders() {
        var resetDelivery = applicationContext.getBean(PasswordResetDelivery.class);
        assertThat(resetDelivery).isInstanceOf(DevPasswordResetMailbox.class);

        var invitationDelivery = applicationContext.getBean(InvitationDelivery.class);
        assertThat(invitationDelivery).isInstanceOf(DevInvitationMailbox.class);
    }

    @Test
    void localProfileBootstrapsLocalDevelopmentOrganizationOnCleanDatabase() throws Exception {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE slug='local-dev' AND status='ACTIVE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM organization WHERE slug='local-dev'", String.class))
                .isEqualTo(DevAuthenticationBootstrap.LOCAL_DEVELOPMENT_NAME);

        var runner = applicationContext.getBeansOfType(ApplicationRunner.class).values().stream()
                .filter(candidate -> candidate.getClass().getSimpleName().contains("DevAuthenticationBootstrap"))
                .findFirst()
                .orElseThrow();
        ApplicationArguments noArguments = new DefaultApplicationArguments(new String[0]);
        runner.run(noArguments);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE slug='local-dev' AND status='ACTIVE'",
                Integer.class)).isEqualTo(1);
    }
}