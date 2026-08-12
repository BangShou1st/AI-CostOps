package com.aicostops.iam.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "aicostops.auth.allow-public-registration=true",
        "aicostops.auth.public-registration-org-slug=login-org",
        "aicostops.auth.jwt-signing-secret=login-test-only-signing-secret-with-more-than-32-bytes"
})
@Tag("integration")
class LoginServiceIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private LoginService loginService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbcTemplate.update("DELETE FROM audit_event");
        jdbcTemplate.update("DELETE FROM invitation");
        jdbcTemplate.update("DELETE FROM role_assignment");
        jdbcTemplate.update("DELETE FROM organization_member");
        jdbcTemplate.update("DELETE FROM user_credential");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("DELETE FROM organization WHERE slug='login-org'");
        jdbcTemplate.update("INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at) VALUES ('Login','login-org','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    }

    @Test
    void logsInWithAccessJwtRefreshSessionAndSanitizedAudit() {
        var registered = register("login@auth.test");

        var result = loginService.login(new LoginCommand(
                " LOGIN@AUTH.TEST ", "valid-password", "203.0.113.10", "browser"));
        var jwt = jwtTokenService.decode(result.accessToken().token());

        assertThat(jwt.getSubject()).isEqualTo(Long.toString(registered.userId()));
        assertThat(jwt.getClaimAsString("sv")).isEqualTo("0");
        assertThat(result.refreshCredential()).contains(".");
        assertThat(redis.keys("aicostops:v1:auth:refresh:*")).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type='LOGIN_SUCCESS' AND actor_user_id=?",
                Integer.class, registered.userId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CAST(metadata_json AS CHAR) FROM audit_event WHERE event_type='LOGIN_SUCCESS'",
                String.class)).doesNotContain("valid-password", result.refreshCredential(), result.accessToken().token());
    }

    @Test
    void returnsTheSameInvalidCredentialsProblemForUnknownEmailAndWrongPassword() {
        register("known@auth.test");

        var unknown = capture(new LoginCommand("unknown@auth.test", "valid-password", "203.0.113.11", "browser"));
        var wrong = capture(new LoginCommand("known@auth.test", "wrong-password", "203.0.113.12", "browser"));

        assertThat(unknown.status()).isEqualTo(wrong.status()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        assertThat(unknown.code()).isEqualTo(wrong.code()).isEqualTo(ProblemCode.AUTH_INVALID_CREDENTIALS);
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
    }

    @Test
    void rejectsDisabledAccountWithoutCreatingARefreshSession() {
        var registered = register("disabled@auth.test");
        jdbcTemplate.update("UPDATE app_user SET status='DISABLED' WHERE id=?", registered.userId());

        var error = capture(new LoginCommand("disabled@auth.test", "valid-password", "203.0.113.13", "browser"));

        assertThat(error.status().value()).isEqualTo(403);
        assertThat(error.code()).isEqualTo(ProblemCode.ACCOUNT_DISABLED);
        assertThat(redis.keys("aicostops:v1:auth:refresh:*")).isEmpty();
    }

    @Test
    void rateLimitsBeforeCredentialVerificationAndReturnsRetryAfter() {
        register("limited@auth.test");
        for (var attempt = 1; attempt <= 8; attempt++) {
            capture(new LoginCommand("limited@auth.test", "wrong-password", "203.0.113.14", "browser"));
        }

        var limited = capture(new LoginCommand(
                "limited@auth.test", "valid-password", "203.0.113.14", "browser"));

        assertThat(limited.status().value()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo(ProblemCode.AUTH_RATE_LIMITED);
        assertThat(limited.retryAfterSeconds()).isBetween(1L, 900L);
        assertThat(redis.keys("aicostops:v1:auth:refresh:*")).isEmpty();
    }

    private RegisteredIdentity register(String email) {
        return registrationService.register(new RegisterCommand(email, "Login User", "valid-password"));
    }

    private DomainException capture(LoginCommand command) {
        try {
            loginService.login(command);
            throw new AssertionError("Expected login to fail");
        } catch (DomainException exception) {
            return exception;
        }
    }
}
