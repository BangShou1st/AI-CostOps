package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DevInvitationMailboxTest {

    @TempDir
    java.nio.file.Path mailbox;

    @Test
    void devMailboxWritesAcceptLinkWithoutLogging(CapturedOutput output) throws Exception {
        var sink = new DevInvitationMailbox(mailbox, "http://localhost:8080/accept-invitation");

        sink.deliver("person@example.com", "invitation-secret-value");

        var messages = Files.list(mailbox).toList();
        assertThat(messages).hasSize(1);
        assertThat(Files.readString(messages.getFirst()))
                .contains("email=person@example.com")
                .contains("acceptLink=http://localhost:8080/accept-invitation?token=invitation-secret-value");
        assertThat(output.getAll()).doesNotContain("invitation-secret-value", "person@example.com");
    }
}
