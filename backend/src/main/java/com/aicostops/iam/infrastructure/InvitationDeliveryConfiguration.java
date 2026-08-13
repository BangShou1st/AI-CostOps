package com.aicostops.iam.infrastructure;

import com.aicostops.iam.application.InvitationDelivery;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;

@Configuration(proxyBeanMethods = false)
public class InvitationDeliveryConfiguration {

    @Bean
    @Profile("!dev")
    InvitationDelivery invitationDelivery() {
        return new InvitationDelivery() {
            @Override
            public void requireAvailable() {
                throw unavailable();
            }

            @Override
            public void deliver(String normalizedEmail, String invitationToken) {
                throw unavailable();
            }
        };
    }

    @Bean
    @Profile("dev")
    InvitationDelivery devInvitationDelivery(
            @Value("${aicostops.iam.dev-invitation-mailbox-path:.local-dev/invitations}") Path mailbox,
            @Value("${aicostops.iam.dev-invite-page-url:http://localhost:8080/accept-invitation}")
            String invitePageUrl) {
        return new DevInvitationMailbox(mailbox, invitePageUrl);
    }

    private static DomainException unavailable() {
        return new DomainException(HttpStatus.SERVICE_UNAVAILABLE,
                ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE,
                "Invitation delivery unavailable", "Invitation delivery is temporarily unavailable.");
    }
}
