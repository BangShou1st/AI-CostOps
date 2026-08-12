package com.aicostops.iam.api;

import com.aicostops.iam.application.RegisterCommand;
import com.aicostops.iam.application.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;

    public AuthController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public RegisteredIdentityResponse register(@Valid @RequestBody RegisterRequest request) {
        return RegisteredIdentityResponse.from(registrationService.register(
                new RegisterCommand(request.email(), request.displayName(), request.password())));
    }
}
