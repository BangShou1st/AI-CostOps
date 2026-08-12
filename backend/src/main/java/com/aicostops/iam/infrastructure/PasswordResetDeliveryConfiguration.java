package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.PasswordResetDelivery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PasswordResetDeliveryConfiguration {
    @Bean
    PasswordResetDelivery passwordResetDelivery() {
        return (email, token) -> { /* Production delivery integration is intentionally secret-safe. */ };
    }
}
