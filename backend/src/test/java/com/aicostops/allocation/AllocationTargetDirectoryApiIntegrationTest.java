package com.aicostops.allocation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Read-only allocation target directory: the same-org ACTIVE project, cost
 * center, and team safe refs the editor picks from, gated by ALLOCATION_EDIT
 * at ORG scope.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class AllocationTargetDirectoryApiIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsSameOrgActiveTargetsOfEveryType() throws Exception {
        mockMvc.perform(get("/api/v1/allocation-targets")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("PROJECT"))
                .andExpect(jsonPath("$[0].id").value(Long.toString(projectId)))
                .andExpect(jsonPath("$[0].name").value("Target"))
                .andExpect(jsonPath("$[1].type").value("COST_CENTER"))
                .andExpect(jsonPath("$[1].id").value(Long.toString(costCenterId)))
                .andExpect(jsonPath("$[2].type").value("TEAM"))
                .andExpect(jsonPath("$[2].id").value(Long.toString(teamId)));
    }

    @Test
    void excludesInactiveTargetsAndOtherOrganizations() throws Exception {
        var inactiveProject = insertTarget("project", orgId, "alloc-dead-" + ++fixtureCounter);
        jdbc.update("UPDATE project SET status='ARCHIVED' WHERE id=?", inactiveProject);
        var foreignProject = insertTarget("project", foreignOrgId,
                "alloc-foreign-p-" + ++fixtureCounter);

        mockMvc.perform(get("/api/v1/allocation-targets")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.id=='%s')]", Long.toString(inactiveProject)).doesNotExist())
                .andExpect(jsonPath("$[?(@.id=='%s')]", Long.toString(foreignProject)).doesNotExist());
    }

    @Test
    void allocationEditPermissionIsRequired() throws Exception {
        revokeAllAssignments();
        createPermissionRole("ALLOC_READER", List.of("COST_READ", "ALLOCATION_READ"));
        assign("ALLOC_READER", "ORG", orgId);

        mockMvc.perform(get("/api/v1/allocation-targets")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void authenticationIsRequired() throws Exception {
        mockMvc.perform(get("/api/v1/allocation-targets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
    }
}
