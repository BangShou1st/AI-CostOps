package com.aicostops.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.aicostops.iam.application.AdminInvitationService;
import com.aicostops.iam.application.RoleAssignmentService;
import com.aicostops.iam.application.RoleCatalogService;
import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.iam.application.UserAdminService;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.organization.application.CostCenterService;
import com.aicostops.organization.application.ProjectMembershipService;
import com.aicostops.organization.application.ProjectService;
import com.aicostops.organization.application.ProviderAccountService;
import com.aicostops.organization.application.TeamMembershipService;
import com.aicostops.organization.application.TeamService;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenService tokens;
    @MockitoBean
    private SecurityVersionService versions;
    @MockitoBean
    private UserAdminService users;
    @MockitoBean
    private RoleCatalogService catalog;
    @MockitoBean
    private RoleAssignmentService roleAssignments;
    @MockitoBean
    private AdminInvitationService invitations;
    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private ProjectMembershipService projectMemberships;
    @MockitoBean
    private TeamService teams;
    @MockitoBean
    private TeamMembershipService teamMemberships;
    @MockitoBean
    private CostCenterService costCenters;
    @MockitoBean
    private ProviderAccountService providerAccounts;

    @Test
    void implementedM1RoutesRequireAuthentication() throws Exception {
        var bearer = bearer();

        for (var operation : implementedM1Operations()) {
            mockMvc.perform(operation.get())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));

            var authenticatedResult = mockMvc.perform(operation.get().header("Authorization", bearer))
                    .andReturn();
            assertThat(authenticatedResult.getResponse().getStatus())
                    .as("authenticated %s %s reaches its controller",
                            authenticatedResult.getRequest().getMethod(),
                            authenticatedResult.getRequest().getRequestURI())
                    .isNotIn(401, 403);
            assertThat(authenticatedResult.getRequest()
                    .getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE))
                    .as("authenticated %s %s resolves a controller handler",
                            authenticatedResult.getRequest().getMethod(),
                            authenticatedResult.getRequest().getRequestURI())
                    .isInstanceOf(HandlerMethod.class);
        }
    }

    @Test
    void unsupportedMethodsRemainDenied() throws Exception {
        var bearer = bearer();

        for (var unsupported : List.of(
                post("/api/v1/users"),
                get("/api/v1/users/123/status"),
                get("/api/v1/role-assignments"),
                get("/api/v1/invitations"),
                delete("/api/v1/projects/123"),
                get("/api/v1/projects/123"),
                get("/api/v1/projects/123/members/456"),
                delete("/api/v1/teams/123"),
                get("/api/v1/teams/123"),
                get("/api/v1/teams/123/members/456"),
                delete("/api/v1/cost-centers/123"),
                get("/api/v1/cost-centers/123"),
                delete("/api/v1/provider-accounts/123"),
                get("/api/v1/provider-accounts/123"),
                get("/api/v1/not-implemented"))) {
            mockMvc.perform(unsupported.header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        mockMvc.perform(post("/api/v1/invitations/public-token/accept")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void m3AndUnimplementedRoutesRemainDenied() throws Exception {
        var bearer = bearer();

        // M2 Group 3 implemented /api/v1/evidence and /api/v1/imports; the
        // canonical-cost families remain unimplemented and must stay denyAll.
        for (var routeFamily : List.of(
                "/api/v1/costs/charges", "/api/v1/budgets", "/api/v1/ledger")) {
            for (var path : List.of(routeFamily, routeFamily + "/123")) {
                for (var method : List.of(
                        HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT, HttpMethod.DELETE)) {
                    mockMvc.perform(request(method, path).header("Authorization", bearer))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
                }
            }
        }
    }

    @Test
    void permitsHealthChecksWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    void requiresAuthenticationForEveryOtherRequest() throws Exception {
        mockMvc.perform(get("/api/v1/not-implemented"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.instance").value("/api/v1/not-implemented"));
    }

    @Test
    void invalidBearerUsesTheSameProblemDetailContract() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/me"));
    }

    @Test
    void authenticatedDeniedRequestUsesForbiddenProblemDetailContract() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        mockMvc.perform(get("/api/v1/not-implemented").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").isString())
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.instance").value("/api/v1/not-implemented"));
    }

    @Test
    void exactIamReadRoutesRequireAuthentication() throws Exception {
        for (var path : java.util.List.of(
                "/api/v1/users", "/api/v1/users/123", "/api/v1/roles", "/api/v1/permissions")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        }
    }

    @Test
    void unsupportedMethodsOnIamReadPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var path : java.util.List.of(
                "/api/v1/users", "/api/v1/users/123", "/api/v1/roles", "/api/v1/permissions")) {
            mockMvc.perform(post(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void iamReadMatchersDoNotOpenRouteFamilies() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var path : java.util.List.of(
                "/api/v1/users/123/roles", "/api/v1/roles/123", "/api/v1/permissions/123")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void exactIamMutationRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(patch("/api/v1/users/123/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        mockMvc.perform(post("/api/v1/role-assignments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        mockMvc.perform(delete("/api/v1/role-assignments/123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        mockMvc.perform(post("/api/v1/invitations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
    }

    @Test
    void unsupportedMethodsOnIamMutationPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                post("/api/v1/users/123/status"),
                patch("/api/v1/role-assignments"),
                post("/api/v1/role-assignments/123"),
                patch("/api/v1/invitations"),
                get("/api/v1/invitations/not-an-operation"),
                get("/api/v1/invitations/public-token/accept"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void invitationAcceptancePostRemainsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/invitations/public-token/accept")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void exactProjectRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        mockMvc.perform(post("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        mockMvc.perform(patch("/api/v1/projects/123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
    }

    @Test
    void unsupportedProjectMethodsAndFamilyPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                delete("/api/v1/projects"),
                patch("/api/v1/projects"),
                get("/api/v1/projects/123"),
                post("/api/v1/projects/123"),
                delete("/api/v1/projects/123"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void exactTeamRoutesRequireAuthentication() throws Exception {
        for (var request : java.util.List.of(
                get("/api/v1/teams"),
                post("/api/v1/teams"),
                patch("/api/v1/teams/123"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        }
    }

    @Test
    void unsupportedTeamMethodsAndFamilyPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                patch("/api/v1/teams"),
                put("/api/v1/teams"),
                delete("/api/v1/teams"),
                get("/api/v1/teams/123"),
                post("/api/v1/teams/123"),
                put("/api/v1/teams/123"),
                delete("/api/v1/teams/123"),
                get("/api/v1/teams/123/extra"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void exactCostCenterRoutesRequireAuthentication() throws Exception {
        for (var request : java.util.List.of(
                get("/api/v1/cost-centers"),
                post("/api/v1/cost-centers"),
                patch("/api/v1/cost-centers/123"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        }
    }

    @Test
    void unsupportedCostCenterMethodsAndFamilyPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                patch("/api/v1/cost-centers"),
                put("/api/v1/cost-centers"),
                delete("/api/v1/cost-centers"),
                get("/api/v1/cost-centers/123"),
                post("/api/v1/cost-centers/123"),
                put("/api/v1/cost-centers/123"),
                delete("/api/v1/cost-centers/123"),
                get("/api/v1/cost-centers/123/extra"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void exactProviderAccountRoutesRequireAuthentication() throws Exception {
        for (var request : java.util.List.of(
                get("/api/v1/provider-accounts"),
                post("/api/v1/provider-accounts"),
                patch("/api/v1/provider-accounts/123"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        }
    }

    @Test
    void unsupportedProviderAccountMethodsAndFamilyPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                patch("/api/v1/provider-accounts"),
                put("/api/v1/provider-accounts"),
                delete("/api/v1/provider-accounts"),
                get("/api/v1/provider-accounts/123"),
                post("/api/v1/provider-accounts/123"),
                put("/api/v1/provider-accounts/123"),
                delete("/api/v1/provider-accounts/123"),
                get("/api/v1/provider-accounts/123/extra"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void exactProjectMembershipRoutesRequireAuthentication() throws Exception {
        for (var request : java.util.List.of(
                get("/api/v1/projects/123/members"),
                post("/api/v1/projects/123/members"),
                delete("/api/v1/projects/123/members/456"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        }
    }

    @Test
    void unsupportedProjectMembershipMethodsAndFamilyPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                patch("/api/v1/projects/123/members"),
                delete("/api/v1/projects/123/members"),
                put("/api/v1/projects/123/members"),
                get("/api/v1/projects/123/members/456"),
                post("/api/v1/projects/123/members/456"),
                patch("/api/v1/projects/123/members/456"),
                put("/api/v1/projects/123/members/456"),
                delete("/api/v1/projects/123/members/456/extra"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void exactTeamMembershipRoutesRequireAuthentication() throws Exception {
        for (var request : java.util.List.of(
                get("/api/v1/teams/123/members"),
                post("/api/v1/teams/123/members"),
                delete("/api/v1/teams/123/members/456"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_ACCESS_EXPIRED"));
        }
    }

    @Test
    void unsupportedTeamMembershipMethodsAndFamilyPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                patch("/api/v1/teams/123/members"),
                delete("/api/v1/teams/123/members"),
                put("/api/v1/teams/123/members"),
                get("/api/v1/teams/123/members/456"),
                post("/api/v1/teams/123/members/456"),
                patch("/api/v1/teams/123/members/456"),
                put("/api/v1/teams/123/members/456"),
                delete("/api/v1/teams/123/members/456/extra"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    private List<Supplier<MockHttpServletRequestBuilder>> implementedM1Operations() {
        return List.of(
                () -> get("/api/v1/users"),
                () -> get("/api/v1/users/123"),
                () -> patch("/api/v1/users/123/status").contentType("application/json").content("{}"),
                () -> get("/api/v1/roles"),
                () -> get("/api/v1/permissions"),
                () -> post("/api/v1/role-assignments").contentType("application/json").content("{}"),
                () -> delete("/api/v1/role-assignments/123"),
                () -> post("/api/v1/invitations").contentType("application/json").content("{}"),
                () -> get("/api/v1/projects"),
                () -> post("/api/v1/projects").contentType("application/json").content("{}"),
                () -> patch("/api/v1/projects/123").contentType("application/json").content("{}"),
                () -> get("/api/v1/projects/123/members"),
                () -> post("/api/v1/projects/123/members").contentType("application/json").content("{}"),
                () -> delete("/api/v1/projects/123/members/456"),
                () -> get("/api/v1/teams"),
                () -> post("/api/v1/teams").contentType("application/json").content("{}"),
                () -> patch("/api/v1/teams/123").contentType("application/json").content("{}"),
                () -> get("/api/v1/teams/123/members"),
                () -> post("/api/v1/teams/123/members").contentType("application/json").content("{}"),
                () -> delete("/api/v1/teams/123/members/456"),
                () -> get("/api/v1/cost-centers"),
                () -> post("/api/v1/cost-centers").contentType("application/json").content("{}"),
                () -> patch("/api/v1/cost-centers/123").contentType("application/json").content("{}"),
                () -> get("/api/v1/provider-accounts"),
                () -> post("/api/v1/provider-accounts").contentType("application/json").content("{}"),
                () -> patch("/api/v1/provider-accounts/123").contentType("application/json").content("{}"));
    }

    private String bearer() {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        return "Bearer " + tokens.issue(42L, 0L).token();
    }
}
