package com.aicostops.iam.infrastructure;

import com.aicostops.organization.infrastructure.OrganizationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevAuthenticationBootstrap implements ApplicationRunner {

    static final String LOCAL_DEVELOPMENT_NAME = "AI CostOps Local Development";

    private final OrganizationMapper organizationMapper;
    private final String organizationSlug;

    public DevAuthenticationBootstrap(
            OrganizationMapper organizationMapper,
            @Value("${aicostops.auth.public-registration-org-slug:local-dev}") String organizationSlug) {
        this.organizationMapper = organizationMapper;
        this.organizationSlug = organizationSlug;
    }

    @Override
    public void run(ApplicationArguments args) {
        organizationMapper.insertActiveOrganizationIfMissing(organizationSlug, LOCAL_DEVELOPMENT_NAME);
    }
}
