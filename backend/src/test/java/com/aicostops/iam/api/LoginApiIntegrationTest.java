package com.aicostops.iam.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
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

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=login-api-org",
        "aicostops.auth.jwt-signing-secret=login-api-test-only-signing-secret-with-more-than-32-bytes",
        "aicostops.auth.refresh-cookie-secure=true"
})
@AutoConfigureMockMvc
@Tag("integration")
class LoginApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbcTemplate.update("DELETE FROM audit_event");
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM organization WHERE slug='login-api-org'");
        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Login API','login-api-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        registrationService.register(new RegisterCommand("api-login@auth.test", "API Login", "valid-password"));
    }

    @Test
    void returnsAccessTokenAndHttpOnlyRefreshCookieWithoutJsonRefreshSecret() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr("203.0.113.20"); return request; })
                        .header("User-Agent", "integration-browser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"api-login@auth.test","password":"valid-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.user.displayName").value("API Login"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("aicostops_refresh="),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"))));
    }

    @Test
    void mapsInvalidCredentialsToTheFrozenProblemCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"api-login@auth.test","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void mapsValidationAndMalformedJsonToStableProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
    }
}
