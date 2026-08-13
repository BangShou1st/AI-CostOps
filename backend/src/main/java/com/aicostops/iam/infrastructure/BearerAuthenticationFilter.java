package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.security.SecurityProblemWriter;
import com.aicostops.shared.web.ProblemCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokens;
    private final SecurityVersionService versions;
    private final SecurityProblemWriter problems;

    public BearerAuthenticationFilter(JwtTokenService tokens, SecurityVersionService versions,
            SecurityProblemWriter problems) {
        this.tokens = tokens;
        this.versions = versions;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        long userId;
        long tokenVersion;
        try {
            var jwt = tokens.decode(header.substring(7));
            userId = Long.parseLong(jwt.getSubject());
            tokenVersion = Long.parseLong(jwt.getClaimAsString("sv"));
        } catch (JwtException | IllegalArgumentException exception) {
            problems.unauthorized(request, response, ProblemCode.AUTH_ACCESS_EXPIRED,
                    "Access token is invalid or expired.");
            return;
        }
        var current = versions.current(userId);
        if (current == null || current.longValue() != tokenVersion) {
            problems.unauthorized(request, response, ProblemCode.AUTH_SESSION_EXPIRED,
                    "Authentication session expired.");
            return;
        }
        var principal = new AuthenticatedUser(userId, tokenVersion);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        chain.doFilter(request, response);
    }
}
