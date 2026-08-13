package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.PasswordResetDelivery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class PasswordResetDeliveryConfiguration {
    @Bean
    @Profile("!dev")
    PasswordResetDelivery passwordResetDelivery() {
        return (email, token) -> { /* Production delivery integration is intentionally secret-safe. */ };
    }

    @Bean
    @Profile("dev")
    PasswordResetDelivery devPasswordResetDelivery(
            @Value("${aicostops.auth.dev-mailbox-path:.local-dev/mailbox}") Path mailbox,
            @Value("${aicostops.auth.dev-reset-page-url:http://localhost:8080/reset-password}") String resetPageUrl) {
        return new DevPasswordResetMailbox(mailbox, resetPageUrl);
    }
}
