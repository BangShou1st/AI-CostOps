package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.testsupport.MySqlContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=local-dev"
})
@ActiveProfiles("dev")
@Tag("integration")
class DevAuthenticationBootstrapIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void devProfileCreatesTheLocalDevelopmentOrganizationIdempotently() throws Exception {
        assertThat(localDevelopmentOrganizations()).isEqualTo(1);

        var runner = applicationContext.getBeansOfType(ApplicationRunner.class).values().stream()
                .filter(candidate -> candidate.getClass().getSimpleName().contains("DevAuthenticationBootstrap"))
                .findFirst()
                .orElseThrow();
        ApplicationArguments noArguments = new DefaultApplicationArguments(new String[0]);
        runner.run(noArguments);

        assertThat(localDevelopmentOrganizations()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM organization WHERE slug='local-dev'", String.class))
                .isEqualTo("AI CostOps Local Development");
    }

    private int localDevelopmentOrganizations() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization WHERE slug='local-dev' AND status='ACTIVE'", Integer.class);
    }
}
