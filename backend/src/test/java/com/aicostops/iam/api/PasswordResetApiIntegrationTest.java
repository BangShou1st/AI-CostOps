package com.aicostops.iam.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.application.PasswordResetDelivery;
import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=reset-org",
        "aicostops.auth.jwt-signing-secret=reset-test-only-signing-secret-with-more-than-32-bytes"
})
@AutoConfigureMockMvc
@Tag("integration")
class PasswordResetApiIntegrationTest extends AuthenticationContainersSupport {
    @Autowired MockMvc mockMvc;
    @Autowired RegistrationService registration;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockitoBean PasswordResetDelivery delivery;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("DELETE FROM audit_event"); jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member"); jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user"); jdbc.update("DELETE FROM organization WHERE slug='reset-org'");
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Reset','reset-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        registration.register(new RegisterCommand("reset@auth.test", "Reset User", "old-password"));
    }

    @Test
    void forgotHasGenericShapeAndDoesNotEnumerateAccounts() throws Exception {
        forgot("unknown@auth.test").andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(true));
        verify(delivery, never()).deliver(anyString(), anyString());
        forgot("reset@auth.test").andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(true));
        verify(delivery).deliver(org.mockito.ArgumentMatchers.eq("reset@auth.test"), anyString());
    }

    @Test
    void resetIsSingleUseChangesPasswordAndInvalidatesOldAccessAndRefresh() throws Exception {
        var login = login("old-password").andExpect(status().isOk()).andReturn();
        var oldAccess = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        Cookie oldRefresh = login.getResponse().getCookie("aicostops_refresh");
        forgot("reset@auth.test");
        var token = ArgumentCaptor.forClass(String.class);
        verify(delivery).deliver(org.mockito.ArgumentMatchers.eq("reset@auth.test"), token.capture());

        mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token.getValue() + "\",\"newPassword\":\"new-password\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token.getValue() + "\",\"newPassword\":\"another-password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        login("old-password").andExpect(status().isUnauthorized());
        login("new-password").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").header("Origin", "http://localhost:8080").cookie(oldRefresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredResetAndDisabledAccount() throws Exception {
        forgot("reset@auth.test");
        var token = ArgumentCaptor.forClass(String.class);
        verify(delivery).deliver(org.mockito.ArgumentMatchers.eq("reset@auth.test"), token.capture());
        var tokenId = token.getValue().substring(0, token.getValue().indexOf('.'));
        redis.delete("aicostops:v1:auth:reset:" + tokenId);
        reset(token.getValue()).andExpect(status().isUnauthorized());

        clearInvocations(delivery);
        forgot("reset@auth.test");
        token = ArgumentCaptor.forClass(String.class);
        verify(delivery).deliver(org.mockito.ArgumentMatchers.eq("reset@auth.test"), token.capture());
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE email_normalized='reset@auth.test'");
        reset(token.getValue()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void rateLimitsForgotWithRetryAfter() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) forgot("unknown@auth.test").andExpect(status().isAccepted());
        forgot("unknown@auth.test").andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
    }

    private org.springframework.test.web.servlet.ResultActions forgot(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"reset@auth.test\",\"password\":\"" + password + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions reset(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"new-password\"}"));
    }
}
