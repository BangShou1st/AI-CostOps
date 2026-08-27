package com.aicostops.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
import com.aicostops.audit.application.AuditService;
import com.aicostops.iam.infrastructure.RedisRefreshSessionRepository;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=lifecycle-org",
        "aicostops.auth.jwt-signing-secret=lifecycle-test-only-signing-secret-more-than-32-bytes",
        "aicostops.auth.refresh-race-window=10s",
        "aicostops.auth.allowed-origins=http://localhost:8080"
})
@AutoConfigureMockMvc
@Tag("integration")
class RefreshAndLogoutApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired MockMvc mockMvc;
    @Autowired RegistrationService registrationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean RedisRefreshSessionRepository refreshSessions;
    @MockitoSpyBean AuditService auditService;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM role_assignment");
        // M6 close/reconciliation history references organization members.
        jdbc.update("DELETE FROM period_close_check");
        jdbc.update("DELETE FROM period_close_run");
        jdbc.update("DELETE FROM reconciliation_case");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization WHERE slug='lifecycle-org'");
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Lifecycle','lifecycle-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        registrationService.register(new RegisterCommand("lifecycle@auth.test", "Lifecycle", "valid-password"));
    }

    @Test
    void authenticatesMeAndRejectsReplay() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        var firstCookie = login.getResponse().getCookie("aicostops_refresh");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("lifecycle@auth.test"));
        var userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized='lifecycle@auth.test'", Long.class);
        org.assertj.core.api.Assertions.assertThat(redis.opsForValue().get("aicostops:v1:auth:security:" + userId))
                .isEqualTo("0");
        org.assertj.core.api.Assertions.assertThat(redis.getExpire("aicostops:v1:auth:security:" + userId)).isPositive();

        var refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(firstCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_RACE"));
        var sessionId = firstCookie.getValue().substring(0, firstCookie.getValue().indexOf('.'));
        redis.opsForHash().put("aicostops:v1:auth:refresh:" + sessionId, "previous_valid_until_ms", "0");
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(firstCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_REPLAY"));

    }

    @Test
    void logoutRevokesAnOtherwiseLiveRefreshAndRemainsRepeatSafe() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        var liveCookie = login.getResponse().getCookie("aicostops_refresh");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .header("Origin", "http://localhost:8080").cookie(liveCookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(liveCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .header("Origin", "http://localhost:8080"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutRedisFailureReturnsUnavailableWithoutClearingCookieOrRecordingSuccess() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        var liveCookie = login.getResponse().getCookie("aicostops_refresh");
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis unavailable"))
                .when(refreshSessions).revoke(liveCookie.getValue());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .header("Origin", "http://localhost:8080").cookie(liveCookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REDIS_UNAVAILABLE_FOR_AUTH"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        org.mockito.Mockito.verify(auditService, org.mockito.Mockito.never()).append(
                org.mockito.ArgumentMatchers.eq("LOGOUT"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void loginLogoutAndLogoutAllWritePositiveAuditRows() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(
                login.getResponse().getContentAsString(), "$.accessToken");
        var cookie = login.getResponse().getCookie("aicostops_refresh");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .header("Origin", "http://localhost:8080").cookie(cookie))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());

        var userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE email_normalized='lifecycle@auth.test'", Long.class);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='LOGIN_SUCCESS' AND actor_user_id=?",
                Integer.class, userId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='LOGOUT' AND actor_user_id=?",
                Integer.class, userId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='SESSION_REVOKED' AND actor_user_id=?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void rejectsForeignOriginAndLogoutAllDurablyInvalidatesOldJwtAndRefresh() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        var cookie = login.getResponse().getCookie("aicostops_refresh");

        mockMvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://evil.example").cookie(cookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRejectsAValidJwtWhenActiveMembershipContextHasVanished() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        jdbc.update("DELETE ra FROM role_assignment ra JOIN organization_member om ON om.id=ra.org_member_id "
                + "JOIN app_user u ON u.id=om.user_id WHERE u.email_normalized='lifecycle@auth.test'");
        jdbc.update("DELETE om FROM organization_member om JOIN app_user u ON u.id=om.user_id "
                + "WHERE u.email_normalized='lifecycle@auth.test'");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
    }

    @Test
    void missingRedisRefreshStateIsSafelyRejectedWithoutCreatingAnotherSession() throws Exception {
        var login = login();
        var liveCookie = login.getResponse().getCookie("aicostops_refresh");
        var sessionId = liveCookie.getValue().substring(0, liveCookie.getValue().indexOf('.'));
        redis.delete("aicostops:v1:auth:refresh:" + sessionId);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(liveCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));

        org.assertj.core.api.Assertions.assertThat(redis.keys("aicostops:v1:auth:refresh:*")).isEmpty();
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"lifecycle@auth.test\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk()).andReturn();
    }
}
