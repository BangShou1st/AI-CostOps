package com.aicostops.shared.security;

import com.aicostops.iam.application.SecurityVersionService;
import com.aicostops.iam.infrastructure.JwtTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokens;
    private final SecurityVersionService versions;

    public BearerAuthenticationFilter(JwtTokenService tokens, SecurityVersionService versions) {
        this.tokens = tokens;
        this.versions = versions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            var jwt = tokens.decode(header.substring(7));
            var userId = Long.parseLong(jwt.getSubject());
            var tokenVersion = Long.parseLong(jwt.getClaimAsString("sv"));
            var current = versions.current(userId);
            if (current == null || current != tokenVersion) {
                reject(response, "AUTH_SESSION_EXPIRED", "Authentication session expired");
                return;
            }
            var principal = new AuthenticatedUser(userId, tokenVersion);
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
            chain.doFilter(request, response);
        } catch (Exception exception) {
            reject(response, "AUTH_ACCESS_EXPIRED", "Access token is invalid or expired");
        }
    }

    private void reject(HttpServletResponse response, String code, String detail) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\""
                + detail + "\",\"code\":\"" + code + "\"}");
    }
}
