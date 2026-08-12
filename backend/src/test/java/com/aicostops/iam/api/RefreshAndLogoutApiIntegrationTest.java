package com.aicostops.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
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

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization WHERE slug='lifecycle-org'");
        jdbc.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Lifecycle','lifecycle-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        registrationService.register(new RegisterCommand("lifecycle@auth.test", "Lifecycle", "valid-password"));
    }

    @Test
    void authenticatesMeRotatesRefreshRejectsReplayAndLogsOutRepeatSafely() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        var firstCookie = login.getResponse().getCookie("aicostops_refresh");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("lifecycle@auth.test"));

        var refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(firstCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();
        var secondCookie = refreshed.getResponse().getCookie("aicostops_refresh");

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

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .header("Origin", "http://localhost:8080").cookie(secondCookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .header("Origin", "http://localhost:8080"))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsForeignOriginAndLogoutAllDurablyInvalidatesOldJwtAndRefresh() throws Exception {
        var login = login();
        var access = com.jayway.jsonpath.JsonPath.<String>read(login.getResponse().getContentAsString(), "$.accessToken");
        var cookie = login.getResponse().getCookie("aicostops_refresh");

        mockMvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://evil.example").cookie(cookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_EXPIRED"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header("Origin", "http://localhost:8080").cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"lifecycle@auth.test\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk()).andReturn();
    }
}
