package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DefaultInvitationDeliveryTest {

    @Test
    void defaultDeliveryFailsClosed() {
        var delivery = new InvitationDeliveryConfiguration().invitationDelivery();

        var error = catchThrowableOfType(DomainException.class,
                () -> delivery.deliver("person@example.com", "invitation-secret-value"));

        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(error.code()).isEqualTo(ProblemCode.DEPENDENCY_TEMPORARILY_UNAVAILABLE);
        assertThat(error.getMessage()).doesNotContain("person@example.com", "invitation-secret-value");
    }
}
