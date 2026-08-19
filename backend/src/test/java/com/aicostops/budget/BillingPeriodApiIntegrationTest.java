package com.aicostops.budget;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=billing-period-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class BillingPeriodApiIntegrationTest extends BudgetTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsOnlyCurrentOrganizationPeriodsInDeterministicOrder() throws Exception {
        var latest = insertBillingPeriod(orgId,
                "2026-10-01 00:00:00.000000", "2026-11-01 00:00:00.000000", "OPEN");
        var sameStartLower = insertBillingPeriod(orgId,
                "2026-09-01 00:00:00.000000", "2026-10-01 00:00:00.000000", "OPEN");
        var sameStartHigher = insertBillingPeriod(orgId,
                "2026-09-01 00:00:00.000000", "2026-12-01 00:00:00.000000", "CLOSING");
        var foreignPeriod = insertBillingPeriod(foreignOrgId,
                "2026-11-01 00:00:00.000000", "2026-12-01 00:00:00.000000", "OPEN");

        mockMvc.perform(get("/api/v1/billing-periods")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.toString(latest)))
                .andExpect(jsonPath("$[1].id").value(Long.toString(sameStartHigher)))
                .andExpect(jsonPath("$[1].status").value("CLOSING"))
                .andExpect(jsonPath("$[2].id").value(Long.toString(sameStartLower)))
                .andExpect(jsonPath("$[0].periodStart").isString())
                .andExpect(jsonPath("$[0].periodEnd").isString())
                .andExpect(jsonPath("$[0].version").isNumber())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(Long.toString(foreignPeriod)))));
    }

    @Test
    void listRequiresOrganizationBudgetRead() throws Exception {
        mockMvc.perform(get("/api/v1/billing-periods")
                        .header("Authorization", projectOwnerBearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/billing-periods"))
                .andExpect(status().isUnauthorized());
    }
}
