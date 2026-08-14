package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.shared.security.SecurityProblemWriter;
import jakarta.servlet.ServletException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class BearerAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void preservesAuthenticatedDownstreamExceptionsInsteadOfRewritingThemAsInvalidJwt() {
        var tokens = new JwtTokenService(
                "filter-test-only-signing-secret-with-more-than-32-bytes",
                Duration.ofMinutes(15), Clock.systemUTC());
        var versions = mock(SecurityVersionService.class);
        when(versions.current(42L)).thenReturn(3L);
        var filter = new BearerAuthenticationFilter(tokens, versions, mock(SecurityProblemWriter.class));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokens.issue(42L, 3L).token());
        var response = new MockHttpServletResponse();
        var downstream = new ServletException("downstream failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> { throw downstream; }))
                .isSameAs(downstream);
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisDownCannotKeepOldJwtValid() throws Exception {
        var tokens = new JwtTokenService(
                "filter-test-only-signing-secret-with-more-than-32-bytes",
                Duration.ofMinutes(15), Clock.systemUTC());
        var redis = mock(StringRedisTemplate.class);
        var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        var iam = mock(IamMapper.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("aicostops:v1:auth:security:42")).thenReturn("3");
        when(iam.findActiveSecurityVersion(42L)).thenReturn(4L);
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(values).set("aicostops:v1:auth:security:42", "4", Duration.ofMinutes(1));
        var versions = new SecurityVersionService(redis, iam, Duration.ofMinutes(1));
        var filter = new BearerAuthenticationFilter(tokens, versions,
                new SecurityProblemWriter(new ObjectMapper()));
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + tokens.issue(42L, 3L).token());
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("code").stringValue())
                .isEqualTo("AUTH_SESSION_EXPIRED");
    }
}
