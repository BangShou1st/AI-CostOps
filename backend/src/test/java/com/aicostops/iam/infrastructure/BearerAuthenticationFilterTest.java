package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aicostops.iam.application.SecurityVersionService;
import jakarta.servlet.ServletException;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.aicostops.shared.security.SecurityProblemWriter;

class BearerAuthenticationFilterTest {
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
}
