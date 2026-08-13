package com.aicostops.iam.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevPasswordResetMailboxTest {
    @TempDir java.nio.file.Path mailbox;

    @Test
    void writesADeveloperReadableResetLinkWithoutUsingLogsOrApiResponse() throws Exception {
        var sink = new DevPasswordResetMailbox(mailbox, "http://localhost:8080/reset-password");

        sink.deliver("person@example.com", "token-id.secret-value");

        var messages = Files.list(mailbox).toList();
        assertThat(messages).hasSize(1);
        var content = Files.readString(messages.getFirst());
        assertThat(content).contains("person@example.com")
                .contains("http://localhost:8080/reset-password?token=token-id.secret-value");
    }
}
