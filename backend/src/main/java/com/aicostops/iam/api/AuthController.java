package com.aicostops.iam.api;

import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
import com.aicostops.iam.application.LoginCommand;
import com.aicostops.iam.application.LoginService;
import com.aicostops.iam.application.LogoutService;
import com.aicostops.iam.application.RefreshService;
import com.aicostops.iam.application.PasswordResetService;
import com.aicostops.iam.infrastructure.IamMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final RefreshService refreshService;
    private final LogoutService logoutService;
    private final IamMapper iamMapper;
    private final Set<String> allowedOrigins;
    private final PasswordResetService passwordResetService;

    public AuthController(
            RegistrationService registrationService,
            LoginService loginService,
            RefreshService refreshService,
            LogoutService logoutService,
            PasswordResetService passwordResetService,
            IamMapper iamMapper,
            @Value("${aicostops.auth.refresh-cookie-secure:true}") boolean refreshCookieSecure,
            @Value("${aicostops.auth.refresh-session-lifetime:7d}") Duration refreshSessionLifetime,
            @Value("${aicostops.auth.allowed-origins:http://localhost:8080}") String allowedOrigins) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshSessionLifetime = refreshSessionLifetime;
        this.refreshService = refreshService;
        this.logoutService = logoutService;
        this.iamMapper = iamMapper;
        this.passwordResetService = passwordResetService;
        this.allowedOrigins = Set.copyOf(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
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
        setRefreshCookie(servletResponse, result.refreshCredential());
        return LoginResponse.from(result);
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@CookieValue(name = "aicostops_refresh", required = false) String credential,
            HttpServletRequest request, HttpServletResponse response) {
        validateOrigin(request);
        var result = refreshService.refresh(credential);
        setRefreshCookie(response, result.refreshCredential());
        return LoginResponse.from(result);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return MeResponse.from(iamMapper.findAuthenticatedIdentity(user.userId()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedUser user,
            @CookieValue(name = "aicostops_refresh", required = false) String credential,
            HttpServletRequest request, HttpServletResponse response) {
        validateOrigin(request);
        logoutService.logout(user.userId(), credential);
        clearRefreshCookie(response);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(@AuthenticationPrincipal AuthenticatedUser user, HttpServletResponse response) {
        logoutService.logoutAll(user.userId());
        clearRefreshCookie(response);
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest body,
            HttpServletRequest request) {
        passwordResetService.forgot(body.email(), request.getRemoteAddr());
        return new ForgotPasswordResponse(true);
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        passwordResetService.reset(body.token(), body.newPassword());
    }

    private void setRefreshCookie(HttpServletResponse response, String credential) {
        var cookie = ResponseCookie.from("aicostops_refresh", credential)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(refreshSessionLifetime)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("aicostops_refresh", "")
                .httpOnly(true).secure(refreshCookieSecure).sameSite("Strict").path("/api/v1/auth")
                .maxAge(Duration.ZERO).build().toString());
    }

    private void validateOrigin(HttpServletRequest request) {
        var origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new DomainException(HttpStatus.FORBIDDEN, ProblemCode.FORBIDDEN,
                    "Origin rejected", "The request origin is not allowed.");
        }
    }
}
