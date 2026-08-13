package com.aicostops.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.iam.infrastructure.JwtTokenService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
class SecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenService tokens;
    @MockitoBean
    private SecurityVersionService versions;

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
    }

    @Test
    void unsupportedMethodsOnIamMutationPathsRemainDenied() throws Exception {
        org.mockito.Mockito.when(versions.current(42L)).thenReturn(0L);
        var token = tokens.issue(42L, 0L).token();

        for (var request : java.util.List.of(
                post("/api/v1/users/123/status"),
                patch("/api/v1/role-assignments"),
                post("/api/v1/role-assignments/123"))) {
            mockMvc.perform(request.header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }
}
