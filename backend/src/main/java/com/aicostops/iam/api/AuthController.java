package com.aicostops.iam.api;

import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
import com.aicostops.iam.application.LoginCommand;
import com.aicostops.iam.application.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final boolean refreshCookieSecure;
    private final Duration refreshSessionLifetime;

    public AuthController(
            RegistrationService registrationService,
            LoginService loginService,
            @Value("${aicostops.auth.refresh-cookie-secure:true}") boolean refreshCookieSecure,
            @Value("${aicostops.auth.refresh-session-lifetime:7d}") Duration refreshSessionLifetime) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshSessionLifetime = refreshSessionLifetime;
    }

    @PostMapping("/register")
    public RegisteredIdentityResponse register(@Valid @RequestBody RegisterRequest request) {
        return RegisteredIdentityResponse.from(registrationService.register(
                new RegisterCommand(request.email(), request.displayName(), request.password())));
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        var result = loginService.login(new LoginCommand(
                request.email(), request.password(), servletRequest.getRemoteAddr(),
                servletRequest.getHeader("User-Agent")));
        var cookie = ResponseCookie.from("aicostops_refresh", result.refreshCredential())
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(refreshSessionLifetime)
                .build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return LoginResponse.from(result);
    }
}
